#!/usr/bin/env python3
"""Checks that a rootfs archive can be unpacked by Android's toybox tar, which is what runs on a phone, and that
the agent will not be able to change the system part of it.

Reads the (optionally xz/gzip compressed) archive from the path given, and fails when it contains
hard links (toybox truncates link targets longer than 100 characters) or symlink targets that long,
or entry types toybox does not restore (devices, fifos). It also fails on anything outside the agent's own places
(home, workspace, temporary folders) that is writable by everyone or owned by someone other than root: root enters the
guest by running programs from it, so those would be a way to root. Usage: check-tar.py <archive>
"""
import sys
import tarfile

LIMIT = 100
# Places that are the agent's by design (or are devices); everything else must be root's and closed to others.
AGENT_PLACES = ("tmp", "var/tmp", "home/agent", "workspace", "dev", "proc", "sys", "run/lock")


def agent_place(name: str) -> bool:
    clean = name.lstrip("./")
    return any(clean == p or clean.startswith(p + "/") for p in AGENT_PLACES)


def main(path: str) -> int:
    problems = []
    counts = {"files": 0, "dirs": 0, "symlinks": 0, "other": 0}
    with tarfile.open(path, "r|*") as archive:
        for member in archive:
            if not member.issym() and not agent_place(member.name):
                if member.mode & 0o002 and not (member.isdir() and member.mode & 0o1000):
                    problems.append(f"writable by everyone: {member.name} (mode {member.mode:o})")
                if member.uid != 0:
                    problems.append(f"not owned by root: {member.name} ({member.uid}:{member.gid})")
            if member.islnk():
                problems.append(f"hard link {member.name} -> {member.linkname}")
            elif member.issym():
                counts["symlinks"] += 1
                if len(member.linkname.encode()) > LIMIT:
                    problems.append(f"long symlink target ({len(member.linkname.encode())} bytes): {member.name} -> {member.linkname}")
            elif member.isreg():
                counts["files"] += 1
            elif member.isdir():
                counts["dirs"] += 1
            else:
                counts["other"] += 1
                if member.ischr() or member.isblk() or member.isfifo():
                    problems.append(f"special file {member.name} (type {member.type!r})")
    print(f"{path}: {counts['files']} files, {counts['dirs']} directories, {counts['symlinks']} symlinks, {counts['other']} other")
    for p in problems[:20]:
        print("PROBLEM:", p)
    if len(problems) > 20:
        print(f"... and {len(problems) - 20} more")
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
