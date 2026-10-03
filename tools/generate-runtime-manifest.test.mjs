import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'

const generator = path.join(import.meta.dirname, 'generate-runtime-manifest.mjs')

test('engine manifest describes only its own native artifacts', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'ubuntu-engine-'))
  try {
    for (const file of ['libubuntu_proot.so', 'libubuntu_proot_loader.so', 'libandroid-shmem.so', 'libubuntu_talloc.so']) {
      await writeFile(path.join(dir, file), `fixture ${file}`)
    }
    execFileSync(process.execPath, [generator, dir])
    const manifest = JSON.parse(await readFile(path.join(dir, 'runtime-manifest.json'), 'utf8'))
    assert.equal(manifest.rootfs, undefined)
    assert.equal(manifest.nativeLibraries.length, 4)
    assert.equal(manifest.schemaVersion, 3)
    assert.deepEqual(Object.keys(manifest.entrypoint).sort(), ['guestCommand', 'loaderLibrary', 'prootLibrary'])
    assert.ok(manifest.nativeLibraries.every(item => /^[a-f0-9]{64}$/.test(item.sha256)))
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})
