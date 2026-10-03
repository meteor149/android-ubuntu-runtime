import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { readFile, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'

const projectRoot = path.resolve(import.meta.dirname, '..')
const dist = path.resolve(process.argv[2] ?? path.join(projectRoot, 'runtime', 'dist'))
if (process.argv.length > 3) throw new Error('Only an artifact directory can be supplied')
const versions = parseEnv(await readFile(path.join(projectRoot, 'runtime', 'versions.env'), 'utf8'))
const nativeFiles = [
  ['libubuntu_proot.so', 'libubuntu_proot.so'],
  ['libubuntu_proot_loader.so', 'libubuntu_proot_loader.so'],
  ['libandroid-shmem.so', 'libandroid-shmem.so'],
  ['libubuntu_talloc.so', 'libubuntu_talloc.so'],

]

const nativeLibraries = []
for (const [file, packagedName] of nativeFiles) {
  const artifactPath = path.join(dist, file)
  await stat(artifactPath)
  const actualSha256 = await sha256(artifactPath)
  nativeLibraries.push({ file, packagedName, sha256: actualSha256 })
}

const manifest = {
  schemaVersion: 3,
  available: true,
  runtimeVersion: required(versions, 'RUNTIME_VERSION'),
  abi: 'arm64-v8a',
  nativeLibraries,
  entrypoint: {
    prootLibrary: 'libubuntu_proot.so',
    loaderLibrary: 'libubuntu_proot_loader.so',
    guestCommand: '/bin/bash',
  },
  sources: {
    termuxProotVersion: required(versions, 'TERMUX_PROOT_VERSION'),
    termuxProotCommit: required(versions, 'TERMUX_PROOT_COMMIT'),
    termuxPackagesCommit: required(versions, 'TERMUX_PACKAGES_COMMIT'),
  },
}

await writeFile(path.join(dist, 'runtime-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`)
process.stdout.write(`runtime manifest: ${path.join(dist, 'runtime-manifest.json')}\n`)

async function sha256(file) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}

function parseEnv(text) {
  return Object.fromEntries(
    text.split(/\r?\n/u)
      .map(line => line.trim())
      .filter(line => line !== '' && !line.startsWith('#'))
      .map(line => {
        const separator = line.indexOf('=')
        if (separator <= 0) throw new Error(`Invalid versions.env line: ${line}`)
        return [line.slice(0, separator), line.slice(separator + 1)]
      }),
  )
}

function required(values, name) {
  const value = values[name]
  if (typeof value !== 'string' || value === '') throw new Error(`Missing ${name} in runtime/versions.env`)
  return value
}
