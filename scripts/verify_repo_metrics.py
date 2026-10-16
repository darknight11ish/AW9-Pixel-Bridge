#!/usr/bin/env python3
"""Reproducible repository metrics for AW9-Pixel-Bridge (OPEN-10).

Why this exists
---------------
Every engagement report quotes a file count, a total byte size, and per-file digests
(e.g. "32 tracked files / 524,156 bytes"). Those numbers were produced ad hoc, so a
reviewer could not re-derive them and the documents drifted from the repository. This
script recomputes them from `git ls-files`, which is the authoritative definition of
"tracked": anything untracked (build output, local.properties, reports) is excluded by
construction.

Usage
-----
    python3 scripts/verify_repo_metrics.py                # Markdown table to stdout
    python3 scripts/verify_repo_metrics.py --json         # machine-readable summary
    python3 scripts/verify_repo_metrics.py --rev v1.0.2   # measure a tag/commit
    python3 scripts/verify_repo_metrics.py --out docs/METRICS.md
    python3 scripts/verify_repo_metrics.py --tree         # tree view instead of a table

Only the Python 3 standard library and the `git` binary are required.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

# Files whose "line count" is meaningless; hashing still applies.
BINARY_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf",
    ".zip", ".jar", ".apk", ".aab", ".docx", ".xlsx", ".pptx",
    ".jks", ".keystore", ".so", ".dylib", ".dll", ".exe",
}


def git(repo: Path, *args: str) -> str:
    """Runs git in `repo` and returns stdout, failing loudly on error."""
    result = subprocess.run(
        ["git", *args],
        cwd=str(repo),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(
            "git {} failed ({}): {}".format(
                " ".join(args), result.returncode, result.stderr.strip()
            )
        )
    return result.stdout


def repo_root(explicit: str | None) -> Path:
    """Resolves the repository root, so the script works from any subdirectory."""
    start = Path(explicit).resolve() if explicit else Path.cwd()
    try:
        out = git(start, "rev-parse", "--show-toplevel").strip()
    except SystemExit:
        raise SystemExit(
            "not a git repository: {} (run with --repo or from inside the checkout)".format(start)
        )
    return Path(out)


def tracked_files(repo: Path, rev: str | None) -> list[str]:
    """Tracked paths (relative, POSIX separators) at `rev`, or in the working tree."""
    args = ["ls-files", "-z"]
    if rev:
        # `ls-tree -r` lists a revision's blobs; blobs must be read with `git show`.
        out = git(repo, "ls-tree", "-r", "-z", "--name-only", rev)
    else:
        out = git(repo, *args)
    return sorted(p for p in out.split("\0") if p)


def file_bytes(repo: Path, path: str, rev: str | None) -> bytes:
    """Reads a blob from the revision, or from disk when measuring the working tree."""
    if rev:
        result = subprocess.run(
            ["git", "show", "{}:{}".format(rev, path)],
            cwd=str(repo),
            capture_output=True,
        )
        if result.returncode != 0:
            raise SystemExit("git show {}:{} failed".format(rev, path))
        return result.stdout
    full = repo / path
    if not full.is_file():
        raise SystemExit("tracked file missing from the working tree: {}".format(path))
    return full.read_bytes()


def count_lines(data: bytes) -> int | None:
    """Physical line count, or None for binary content."""
    if b"\0" in data:
        return None
    # latin-1 never raises, so a stray non-UTF-8 byte cannot lose the count.
    text = data.decode("latin-1")
    if not text:
        return 0
    return text.count("\n") + (0 if text.endswith("\n") else 1)


def measure(repo: Path, rev: str | None) -> dict:
    """Collects the per-file metrics and their totals."""
    rows = []
    for path in tracked_files(repo, rev):
        data = file_bytes(repo, path, rev)
        lines = count_lines(data)
        if lines is None or Path(path).suffix.lower() in BINARY_SUFFIXES:
            lines = None
        rows.append(
            {
                "path": path,
                "bytes": len(data),
                "lines": lines,
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )

    return {
        "revision": rev or "working-tree",
        # `^{commit}` dereferences an annotated tag to the commit it points at,
        # otherwise a tag revision would be reported as the tag object's own hash.
        "head": git(repo, "rev-parse", "{}^{{commit}}".format(rev)).strip() if rev else
                git(repo, "rev-parse", "HEAD").strip(),
        "tracked_files": len(rows),
        "tracked_bytes": sum(r["bytes"] for r in rows),
        "tracked_lines": sum(r["lines"] for r in rows if r["lines"] is not None),
        "files": rows,
    }


def render_markdown(metrics: dict) -> str:
    """Markdown report: a summary block plus the per-file table."""
    lines = [
        "# AW9-Pixel-Bridge — tracked repository metrics",
        "",
        "Generated by `scripts/verify_repo_metrics.py` from `git ls-files`, so these",
        "numbers can be reproduced exactly rather than recalled.",
        "",
        "| Field | Value |",
        "|---|---|",
        "| Revision measured | `{}` |".format(metrics["revision"]),
        "| Resolved commit | `{}` |".format(metrics["head"]),
        "| Tracked files | {} |".format(metrics["tracked_files"]),
        "| Total tracked bytes | {} |".format(metrics["tracked_bytes"]),
        "| Total tracked lines (text only) | {} |".format(metrics["tracked_lines"]),
        "",
        "| File | Bytes | Lines | SHA-256 |",
        "|---|---:|---:|---|",
    ]
    for row in metrics["files"]:
        lines.append(
            "| `{}` | {} | {} | `{}` |".format(
                row["path"],
                row["bytes"],
                row["lines"] if row["lines"] is not None else "(binary)",
                row["sha256"],
            )
        )
    lines.append("")
    return "\n".join(lines)


def render_tree(metrics: dict) -> str:
    """Compact tree view: per-directory file counts and byte totals."""
    totals: dict[str, list[int]] = {}
    for row in metrics["files"]:
        parent = str(Path(row["path"]).parent).replace("\\", "/")
        bucket = totals.setdefault(parent, [0, 0])
        bucket[0] += 1
        bucket[1] += row["bytes"]

    lines = ["Tracked tree (directory: files / bytes)", ""]
    for directory in sorted(totals):
        count, size = totals[directory]
        label = directory if directory != "." else "(repository root)"
        lines.append("  {:<44} {:>3} files {:>10} bytes".format(label, count, size))
    lines.append("")
    lines.append(
        "TOTAL {} files, {} bytes".format(metrics["tracked_files"], metrics["tracked_bytes"])
    )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", help="path inside the repository (default: cwd)")
    parser.add_argument("--rev", help="tag or commit to measure (default: working tree)")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of Markdown")
    parser.add_argument("--tree", action="store_true", help="emit a per-directory tree view")
    parser.add_argument("--out", help="write the report to this file instead of stdout")
    args = parser.parse_args(argv)

    metrics = measure(repo_root(args.repo), args.rev)

    if args.json:
        report = json.dumps(metrics, indent=2)
    elif args.tree:
        report = render_tree(metrics)
    else:
        report = render_markdown(metrics)

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(report, encoding="utf-8")
        print(
            "wrote {} ({} tracked files, {} bytes)".format(
                out_path, metrics["tracked_files"], metrics["tracked_bytes"]
            )
        )
    else:
        print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
