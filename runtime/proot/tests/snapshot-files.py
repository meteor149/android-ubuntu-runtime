"""Focused regression for the temporary snapshot, run inside the guest."""
import os
import pathlib
import sys

directory = pathlib.Path(sys.argv[2])
if sys.argv[1] == 'create':
    directory.mkdir()
    (directory / 'original').write_text('snapshot payload')
    os.link(directory / 'original', directory / 'alias')
    os.symlink('alias', directory / 'ordinary-symlink')
    os.symlink('missing', directory / 'dangling-symlink')
else:
    assert (directory / 'original').read_text() == 'snapshot payload'
    assert (directory / 'alias').read_text() == 'snapshot payload'
    entries = {entry.name: entry for entry in os.scandir(directory)}
    for name in ['original', 'alias']:
        assert entries[name].is_file(follow_symlinks=False), name
        assert not entries[name].is_symlink(), name
        assert entries[name].inode() == (directory / name).stat().st_ino, name
    assert entries['ordinary-symlink'].is_symlink()
    assert entries['dangling-symlink'].is_symlink()
    moved = directory.with_name(directory.name + '-renamed')
    directory.rename(moved)
    entries = {entry.name: entry for entry in os.scandir(moved)}
    assert entries['alias'].is_file(follow_symlinks=False)
    assert entries['ordinary-symlink'].is_symlink()
    assert (moved / 'alias').read_text() == 'snapshot payload'
    print('directory entries, ordinary symlinks, renamed parent, and restart: PASS')
