#!/usr/bin/env python3
"""Independently verify a GUI export against the owned fixture's original bytes.

Inputs are local files collected from an authorized device. This performs no ADB
operations, writes no extracted DEX to disk, and makes no arbitrary-app claim.
"""
import argparse
import hashlib
import json
import pathlib
import stat
import struct
import subprocess
import tempfile
import zipfile
import zlib

PACKAGE = 'com.jacklee.haku.containerfixture'
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def archive(path):
    result = {}
    with zipfile.ZipFile(path) as source:
        total = 0
        for item in source.infolist():
            name = item.filename
            parts = pathlib.PurePosixPath(name).parts
            require(name and not name.startswith('/') and '\\' not in name
                    and all(p not in ('', '.', '..') for p in name.split('/')),
                    'Unsafe ZIP entry: ' + name)
            require(not item.is_dir() and not stat.S_ISLNK(item.external_attr >> 16),
                    'Directory or symlink ZIP entry: ' + name)
            require(name not in result, 'Duplicate ZIP entry: ' + name)
            total += item.file_size
            require(total <= MAX_ARCHIVE_BYTES and item.file_size >= 0,
                    'Fixture ZIP exceeds verification size bound')
            require(parts, 'Empty ZIP path')
            result[name] = source.read(item)  # zipfile verifies the entry CRC.
    return result


def standard_dex(data, name):
    require(len(data) >= 112 and data[:4] == b'dex\n' and data[7] == 0,
            'Not standard DEX: ' + name)
    require(data[4:7] in (b'035', b'037', b'038', b'039', b'040'),
            'Unexpected fixture DEX version: ' + name)
    size, header, endian = struct.unpack_from('<III', data, 32)
    require(size == len(data) and header == 112 and endian == 0x12345678,
            'DEX length, header or endian mismatch: ' + name)
    require(hashlib.sha1(data[32:]).digest() == data[12:32],
            'Original DEX SHA1 mismatch: ' + name)
    require((zlib.adler32(data[12:]) & 0xffffffff) == struct.unpack_from('<I', data, 8)[0],
            'Original DEX Adler32 mismatch: ' + name)
    return {'name': name, 'bytes': len(data), 'sha256': sha(data)}


def verify(fixture, report_path, zip_path, records_dir, min_elapsed_ms, dexdump):
    report = json.loads(report_path.read_text())
    require(report.get('schemaVersion') == 2 and report.get('packageName') == PACKAGE,
            'Unexpected export schema or package')
    require(report.get('status') == 'verified' and report.get('engineVerified') is True
            and report.get('packageCopiesVerified') is True
            and report.get('archiveVerified') is True and not report.get('cancelled'),
            'Export is not a verified capture')
    packed = zip_path.read_bytes()
    require(len(packed) == report['zipBytes'] and sha(packed) == report['zipSha256'],
            'Published ZIP byte count or SHA256 mismatch')
    entries = archive(zip_path)
    inventory = report['artifacts']
    names = [item['name'] for item in inventory]
    require(len(names) == len(set(names)) and set(names) == set(entries),
            'ZIP inventory is missing, duplicated or has extra entries')
    for item in inventory:
        data = entries[item['name']]
        require(len(data) == item['bytes'] and sha(data) == item['sha256'],
                'Artifact byte count or SHA256 mismatch: ' + item['name'])
    require(sum(len(v) for v in entries.values()) == report['artifactBytes'],
            'Total artifact byte count mismatch')
    original = fixture.read_bytes()
    require(entries.get('original/base.apk') == original,
            'Original base APK is not byte-exact to the installed fixture candidate')
    require(report['packageCopyBytes'] == len(original), 'Package copy byte count mismatch')
    engine = json.loads(entries['capture-report.json'])
    package_copy = json.loads(entries['package-copy-report.json'])
    require(engine == report['engineReport'] and package_copy == report['packageCopyReport'],
            'Embedded reports differ from their ZIP originals')
    require(engine['packageName'] == PACKAGE and engine['status'] == 'verified'
            and engine['applicationOnCreate'] and engine['activityOnCreate']
            and engine['startedAt'] >= report['startedAt'], 'Engine identity or lifecycle failed')
    runtime = {n: v for n, v in entries.items() if n.startswith('runtime/')}
    expected_names = {'runtime/' + item['name'] for item in engine['artifacts']}
    require(set(runtime) == expected_names, 'Runtime ZIP inventory differs from engine report')
    require(report['runtimeDexBytes'] == sum(map(len, runtime.values())) > 0,
            'Runtime DEX byte count mismatch or empty capture')
    for item in inventory:
        expected_origin = ('runtime_dex' if item['name'].startswith('runtime/') else
                           'package_copy' if item['name'].startswith('original/') else
                           'engine_report' if item['name'] == 'capture-report.json' else
                           'package_report' if item['name'] == 'package-copy-report.json' else None)
        require(item.get('origin') == expected_origin and expected_origin is not None,
                'Artifact origin is incorrect: ' + item['name'])
    for item in engine['artifacts']:
        data = runtime['runtime/' + item['name']]
        require(item['name'] == sha(data) + '.dex'
                and item['bytes'] == len(data) and item['sha256'] == sha(data),
                'Engine content-addressed DEX identity mismatch')
    originals = archive(fixture)
    expected = {'base classes.dex': originals['classes.dex']}
    payloads = {}
    for asset in ('main.xor', 'distinct.xor'):
        raw = bytes(b ^ 0xa7 for b in originals['assets/' + asset])
        expected[asset] = raw
        payloads[sha(raw)] = len(raw)
        require(raw not in originals.values(), 'Clear runtime payload was packaged in APK')
    hashes = {sha(v): v for v in runtime.values()}
    for name, raw in expected.items():
        require(hashes.get(sha(raw)) == raw, 'Missing or changed original runtime DEX: ' + name)
    require(set(hashes) == {sha(raw) for raw in expected.values()},
            'Unexpected support or unknown DEX included in target capture')
    dex_results = [standard_dex(v, n) for n, v in sorted(runtime.items())]
    if dexdump:
        with tempfile.TemporaryDirectory(prefix='haku-dex-verify-') as directory:
            for name, data in runtime.items():
                path = pathlib.Path(directory) / pathlib.PurePosixPath(name).name
                path.write_bytes(data)
                completed = subprocess.run([str(dexdump), '-f', str(path)],
                                           capture_output=True, timeout=30)
                require(completed.returncode == 0,
                        'SDK dexdump rejected ' + name + ': ' + completed.stderr.decode(errors='replace')[-500:])
    records = [json.loads(p.read_text()) for p in sorted(records_dir.glob('*.json'))]
    require(records and all(r['package'] == PACKAGE for r in records), 'Missing owned fixture records')
    require(all(r['applicationStartedElapsedMs'] >= min_elapsed_ms for r in records),
            'Stale fixture record')
    mains = [r for r in records if r['pid'] == engine['capturePid']]
    require(len(mains) == 1, 'Missing unique capture-process fixture record')
    main = mains[0]
    main_flags = ('applicationOnCreate', 'payloadsReady', 'activityOnCreate', 'activityOnResume',
                  'activityDelayedCallback', 'secondActivityRequested', 'secondActivityOnCreate',
                  'secondActivityOnResume', 'secondActivityFinished', 'secondActivityResult')
    require(all(main.get(k) is True for k in main_flags) and main.get('mainActivityResumeCount', 0) >= 2,
            'Fixture main/second Activity lifecycle or result failed')
    require('/virtual/data/user/0/' + PACKAGE + '/files' in main['filesDir'],
            'Fixture did not run in virtual app storage')
    services = [r for r in records if r.get('serviceOnStartCommand')]
    require(services and all(r['pid'] != main['pid'] and r.get('serviceOnCreate') for r in services),
            'Distinct worker process service lifecycle failed')
    for record in [main, *services]:
        require(record['applicationOnCreate'] and record['payloadsReady'], 'Worker Application failed')
        actual = {p['sha256']: p['bytes'] for p in record['payloads']}
        require(actual == payloads and all(p['sourceDeleted'] and p['appLoaderElementRegistered']
                                          for p in record['payloads']),
                'Executed runtime payload identity, source deletion or loader registration failed')
    activity_classes = {a['className'] for a in engine['activities']
                        if a['pid'] == main['pid'] and a['onCreate'] and a['onResume']}
    require({PACKAGE + '.FixtureActivity', PACKAGE + '.SecondActivity'} <= activity_classes,
            'Engine Activity ledger incomplete')
    service_pids = {s['pid'] for s in engine['services'] if s.get('status') == 'started'
                    and s.get('onCreate') and s.get('onStartCommand')}
    require({r['pid'] for r in services} <= service_pids, 'Engine started-service ledger incomplete')
    return {'schema': 'haku.container.fixture.acceptance.v1', 'passed': True,
            'sessionId': engine['sessionId'], 'apiLevel': engine['apiLevel'], 'abi': engine['abi'],
            'fixtureApkSha256': sha(original), 'zipSha256': sha(packed), 'zipBytes': len(packed),
            'packageCopyBytes': len(original), 'runtimeDexBytes': report['runtimeDexBytes'],
            'runtimeDex': dex_results, 'sdkDexdumpPassed': bool(dexdump),
            'gates': ['exact_original_apk', 'exact_runtime_originals', 'dex_header_checksums',
                      'zip_crc_inventory_hashes', 'application_activity_navigation_result',
                      'separate_worker_service', 'deleted_runtime_source_files'],
            'coverage': engine['coverage'],
            'limits': 'Owned fixture only. Worker service lifecycle is verified; worker-only DEX, independent child loaders, later code and rebuilt installable APK are not established by this fixture.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=pathlib.Path, required=True)
    parser.add_argument('--report', type=pathlib.Path, required=True)
    parser.add_argument('--zip', dest='zip_path', type=pathlib.Path, required=True)
    parser.add_argument('--records-dir', type=pathlib.Path, required=True)
    parser.add_argument('--min-elapsed-ms', type=int, default=0)
    parser.add_argument('--dexdump', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    result = verify(args.fixture, args.report, args.zip_path, args.records_dir,
                    args.min_elapsed_ms, args.dexdump)
    rendered = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(rendered)
    print(rendered, end='')


if __name__ == '__main__':
    main()
