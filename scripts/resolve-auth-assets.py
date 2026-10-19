#!/usr/bin/env python3
"""读取本地认证目录配置；不执行 .env，不展开 shell 表达式。"""
import os
from pathlib import Path
import sys

KEY = 'DIPLAY_AUTH_ASSETS_DIR'


def resolve(project, environment):
    value = environment.get(KEY)
    if value is None:
        config = project / '.env'
        if config.is_file():
            for line in config.read_text(encoding='utf-8').splitlines():
                name, separator, raw = line.strip().partition('=')
                if separator and name.strip() == KEY:
                    value = raw.strip()
                    if value.startswith(('"', "'")):
                        if len(value) < 2 or value[-1] != value[0]:
                            raise ValueError(f'{KEY} 引号不完整')
                        value = value[1:-1]
    value = value or '.private/auth-assets'
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ValueError(f'{KEY} 不能包含控制字符')
    path = Path(value).expanduser()
    return path if path.is_absolute() else project / path


if __name__ == '__main__':
    try:
        print(resolve(Path(sys.argv[1]).resolve(), os.environ))
    except (ValueError, OSError):
        sys.exit('认证目录配置无法读取；请检查 DIPLAY_AUTH_ASSETS_DIR 和 .env 格式。')
