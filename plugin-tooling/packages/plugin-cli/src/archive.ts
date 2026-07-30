import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

export interface ArchiveEntry {
  source: string;
  name: string;
}

export interface ArchiveLimits {
  archiveBytes: number;
  extractedBytes: number;
  files: number;
}

export interface ArchiveInspection {
  archiveBytes: number;
  extractedBytes: number;
  files: number;
}

export async function inspectDeterministicZip(
  entries: readonly ArchiveEntry[]
): Promise<ArchiveInspection> {
  let archiveBytes = 22;
  let extractedBytes = 0;
  for (const entry of entries) {
    const info = await stat(entry.source);
    if (!info.isFile()) throw new Error(`Archive entry is not a file: ${entry.source}`);
    const nameBytes = Buffer.byteLength(normalizeArchiveName(entry.name), 'utf8');
    archiveBytes += 30 + nameBytes + info.size + 46 + nameBytes;
    extractedBytes += info.size;
  }
  return {
    archiveBytes,
    extractedBytes,
    files: entries.length
  };
}

export async function writeDeterministicZip(
  target: string,
  entries: readonly ArchiveEntry[],
  limits?: ArchiveLimits
): Promise<void> {
  const sorted = [...entries].sort((left, right) => left.name.localeCompare(right.name, 'en'));
  if (limits) {
    const inspection = await inspectDeterministicZip(sorted);
    if (inspection.files > limits.files) {
      throw new Error(`Plugin package exceeds the ${limits.files} file limit.`);
    }
    if (inspection.extractedBytes > limits.extractedBytes) {
      throw new Error(
        `Plugin package exceeds the ${limits.extractedBytes} byte extracted limit.`
      );
    }
    if (inspection.archiveBytes > limits.archiveBytes) {
      throw new Error(
        `Plugin archive exceeds the ${limits.archiveBytes} byte archive limit.`
      );
    }
  }
  const localParts: Buffer[] = [];
  const centralParts: Buffer[] = [];
  let offset = 0;

  for (const entry of sorted) {
    const info = await stat(entry.source);
    if (!info.isFile()) throw new Error(`Archive entry is not a file: ${entry.source}`);
    const data = await readFile(entry.source);
    const name = Buffer.from(normalizeArchiveName(entry.name), 'utf8');
    const checksum = crc32(data);
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0x0800, 6);
    localHeader.writeUInt16LE(0, 8);
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0x0021, 12);
    localHeader.writeUInt32LE(checksum, 14);
    localHeader.writeUInt32LE(data.length, 18);
    localHeader.writeUInt32LE(data.length, 22);
    localHeader.writeUInt16LE(name.length, 26);
    localHeader.writeUInt16LE(0, 28);
    localParts.push(localHeader, name, data);

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(0x0314, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0x0800, 8);
    centralHeader.writeUInt16LE(0, 10);
    centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0x0021, 14);
    centralHeader.writeUInt32LE(checksum, 16);
    centralHeader.writeUInt32LE(data.length, 20);
    centralHeader.writeUInt32LE(data.length, 24);
    centralHeader.writeUInt16LE(name.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE(0, 38);
    centralHeader.writeUInt32LE(offset, 42);
    centralParts.push(centralHeader, name);
    offset += localHeader.length + name.length + data.length;
  }

  const centralDirectory = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(sorted.length, 8);
  end.writeUInt16LE(sorted.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);

  const payload = Buffer.concat([...localParts, centralDirectory, end]);
  if (limits && payload.length > limits.archiveBytes) {
    throw new Error(
      `Plugin archive exceeds the ${limits.archiveBytes} byte archive limit.`
    );
  }
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = `${target}.tmp-${process.pid}`;
  await writeFile(temporary, payload);
  await rename(temporary, target);
}

function normalizeArchiveName(name: string): string {
  const normalized = name.replaceAll('\\', '/').replace(/^\/+/, '');
  if (!normalized || normalized.split('/').includes('..') || normalized.includes('\0')) {
    throw new Error(`Unsafe archive path: ${name}`);
  }
  return normalized;
}

function crc32(data: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc = CRC_TABLE[(crc ^ byte) & 0xff]! ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

const CRC_TABLE = new Uint32Array(256);
for (let index = 0; index < CRC_TABLE.length; index += 1) {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) {
    value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  }
  CRC_TABLE[index] = value >>> 0;
}
