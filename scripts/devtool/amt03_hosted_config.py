#!/usr/bin/env python3
"""Stage an explicit Devtool instrumentation config ONLY in ephemeral GitHub CI.

The Bash runner backs up the original file and restores it on every exit. The
checked-in consumer policy remains 'disabled' to prevent implicit device tests.
"""
from __future__ import annotations
import argparse
import json
import tomllib
from pathlib import Path

ANDROID = '[targets.app.android]\nvariant = "debug"\naapt2_provider = "auto"\n'
ANDROID_CI = ('[targets.app.android]\nvariant = "debug"\naapt2_provider = "auto"\n'
              'platform = "36"\n'
              'build_tools = { version = "36.1.0" }\n'
              'ndk_version = "27.0.12077973"\n'
              'cmake_version = "3.22.1"\n')
INSTRUMENTATION = '[targets.app.android.instrumentation]\npolicy = "disabled"\n'
INSTRUMENTATION_CI = ('[targets.app.android.instrumentation]\n'
                      'policy = "required"\n'
                      'target_package = "io.github.muntashirakon.AppManager.debug"\n')

def patch(raw: str, *, java_home: str | None = None) -> str:
    for old in (ANDROID, INSTRUMENTATION):
        if raw.count(old) != 1:
            raise ValueError(f'AMT03_HF04_CONFIG_REFUSED: missing/duplicate {old.splitlines()[0]}')
    android_ci = ANDROID_CI
    if java_home is not None:
        # The hosted job passes a concrete JDK21 path, never a JDK inferred
        # from the project's Java 8 source-compatibility setting.
        if (not java_home.strip() or not Path(java_home).is_absolute()
                or any(ch in java_home for ch in ('\n', '\r', '\x00'))):
            raise ValueError('AMT03_HF05_REFUSED: JAVA_HOME must be an absolute safe path')
        android_ci = android_ci.replace('aapt2_provider = "auto"\n',
                'aapt2_provider = "auto"\njava_home = ' + json.dumps(java_home) + '\n', 1)
    modified = raw.replace(ANDROID, android_ci, 1).replace(INSTRUMENTATION, INSTRUMENTATION_CI, 1)
    data = tomllib.loads(modified)
    android = data['targets']['app']['android']
    assert android['build_tools']['version'] == '36.1.0'
    assert android['ndk_version'] == '27.0.12077973'
    assert android['platform'] == '36'
    if java_home is not None and android.get('java_home') != java_home:
        raise ValueError('AMT03_HF05_REFUSED: Devtool JDK path was not bound')
    assert android['instrumentation']['policy'] == 'required'
    assert android['instrumentation']['target_package'] == 'io.github.muntashirakon.AppManager.debug'
    return modified

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('config', type=Path)
    parser.add_argument('--java-home', required=True, help='GitHub-hosted JDK 21 path')
    args = parser.parse_args()
    if not args.config.is_file() or args.config.is_symlink():
        raise SystemExit('AMT03_HF04_CONFIG_REFUSED: expected regular .devtool.toml')
    old = args.config.read_text(encoding='utf8')
    args.config.write_text(patch(old, java_home=args.java_home), encoding='utf8')
    print('AMT03_HF04_EPHEMERAL_CONFIG_READY')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
