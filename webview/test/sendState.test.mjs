import assert from 'node:assert/strict'
import test from 'node:test'
import { prepareSendPayload } from '../build-tests/sendState.js'

test('prepareSendPayload returns null until the bridge is ready', () => {
  assert.equal(prepareSendPayload('hello', false, false), null)
})

test('prepareSendPayload trims text when the bridge is ready', () => {
  assert.deepEqual(prepareSendPayload('  hello  ', false, true), { text: 'hello' })
})

test('prepareSendPayload returns null while a response is streaming', () => {
  assert.equal(prepareSendPayload('hello', true, true), null)
})
