# Temporary PRoot snapshot fixes

Base: Termux PRoot `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`.
The build fetches the checksum-pinned upstream archive; no source tree is vendored.

* `010-upstream-pr394.patch`: both commits from
  [Termux PR #394](https://github.com/termux/proot/pull/394),
  `a1dbd92cc7cee190558762b5c1cd95b862cc456c` and
  `9bf5d3b788bf01ee6c8a94338a46fe7eb9840ceb`, applied to the base.
  Refuse exhausted names, preserve original files, and avoid stale-entry collisions.
* `020-directory-entries.patch`: the minimal local directory-enumeration fix.
  Report recognized simulated hard-link entries with their resolved inode/type
  instead of the host symlink type. See
  [Termux issue #350](https://github.com/termux/proot/issues/350).
  Ordinary symbolic links retain their original type.
* `030-private-store-resolution.patch`: resolve recognized simulated links
  directly to their host backing files. This is needed when the persistent
  private store lives outside the guest rootfs; ordinary symlinks are unchanged.

PR #395 was reviewed but excluded: its broader chain rewrite conflicts with
the newer base, and real hard links remain denied in Android app storage.
This snapshot does not claim complete POSIX hard-link semantics. Anonymous
inode publication, concurrent tracer transactions, and complete inode lifetime
tracking are outside its scope. Applications may still need a publication
strategy suited to their private staging files.

Coordinate: `io.github.meteor149:ubuntu-runtime:0.3.0-SNAPSHOT`.
Repository: `https://central.sonatype.com/repository/maven-snapshots/`.
