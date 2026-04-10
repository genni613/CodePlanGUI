import { copyFileSync, mkdirSync } from 'fs'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const src = resolve(__dirname, '../dist/index.html')
const destDir = resolve(__dirname, '../../src/main/resources/webview')
const dest = resolve(destDir, 'index.html')

mkdirSync(destDir, { recursive: true })
copyFileSync(src, dest)
console.log(`Copied ${src} -> ${dest}`)
