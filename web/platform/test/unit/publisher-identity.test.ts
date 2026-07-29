import assert from 'node:assert/strict';
import test from 'node:test';
import { decidePublisherBinding, validateVersionTransition } from '../../src/worker.js';

test('publisher subject is derived only from immutable GitHub owner id', () => {
  const first = decidePublisherBinding(null, null, 12345);
  const afterRepositoryRename = decidePublisherBinding(first.publisherSubjectId, '12345', 12345);

  assert.deepEqual(first, {
    publisherSubjectId: 'github-owner:12345',
    ownerTransferRequired: false
  });
  assert.deepEqual(afterRepositoryRename, first);
});

test('owner transfer preserves the original subject and freezes the update', () => {
  const transfer = decidePublisherBinding('github-owner:12345', '12345', '67890');

  assert.equal(transfer.publisherSubjectId, 'github-owner:12345');
  assert.equal(transfer.ownerTransferRequired, true);
});

test('invalid GitHub owner ids cannot create publisher subjects', () => {
  assert.throws(() => decidePublisherBinding(null, null, 0), /numeric ID is invalid/);
  assert.throws(() => decidePublisherBinding(null, null, 'owner-login'), /numeric ID is invalid/);
  assert.throws(
    () => decidePublisherBinding('github-owner:12345', 'owner-login', 12345),
    /Stored GitHub owner numeric ID is invalid/
  );
});

test('v3 publication rejects data schema downgrade and requires a migration entrypoint', () => {
  const current = { version: '1.0.0', schemaVersion: 3, dataSchemaVersion: 2 };

  assert.throws(
    () => validateVersionTransition(
      current,
      { version: '2.0.0', dataSchemaVersion: 1, migrationEntrypoint: '' }
    ),
    /data_schema_version/
  );
  assert.throws(
    () => validateVersionTransition(
      current,
      { version: '2.0.0', dataSchemaVersion: 3, migrationEntrypoint: '' }
    ),
    /migration_entrypoint/
  );
  assert.doesNotThrow(() => validateVersionTransition(
    current,
    { version: '2.0.0', dataSchemaVersion: 3, migrationEntrypoint: 'migration.html' }
  ));
});

test('legacy publication can move to the first v3 data schema without an in-place migration', () => {
  assert.doesNotThrow(() => validateVersionTransition(
    { version: '1.9.0', schemaVersion: 2, dataSchemaVersion: 0 },
    { version: '2.0.0', dataSchemaVersion: 1, migrationEntrypoint: '' }
  ));
});
