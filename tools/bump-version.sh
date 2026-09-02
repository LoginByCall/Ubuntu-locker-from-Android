#!/usr/bin/env bash
#
# bump-version.sh — поднять версию продукта.
#
#   tools/bump-version.sh 1.2.0
#
# Пишет номер в VERSION (единственный источник правды: оттуда его берут и
# Gradle, и агент), коммитит и ставит тег vX.Y.Z. Раздел в CHANGELOG.md
# заполняется руками ДО запуска — скрипт лишь проверяет, что он есть.
set -euo pipefail

VERSION_NEW="${1:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

[[ "$VERSION_NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "Использование: $0 <МАЖОРНАЯ.МИНОРНАЯ.ПАТЧ>, например 1.2.0" >&2
    exit 1
}
[[ -z "$(git status --porcelain)" ]] || {
    echo "Рабочее дерево не чисто — сначала закоммитьте или уберите правки." >&2
    exit 1
}
git rev-parse -q --verify "refs/tags/v$VERSION_NEW" >/dev/null && {
    echo "Тег v$VERSION_NEW уже существует." >&2
    exit 1
}
grep -q "^## \[$VERSION_NEW\]" CHANGELOG.md || {
    echo "В CHANGELOG.md нет раздела '## [$VERSION_NEW]' — опишите изменения." >&2
    exit 1
}

echo "$VERSION_NEW" > VERSION
git add VERSION CHANGELOG.md
git commit -m "Версия $VERSION_NEW"
git tag -a "v$VERSION_NEW" -m "Версия $VERSION_NEW"

echo "Готово: VERSION=$VERSION_NEW, тег v$VERSION_NEW."
echo "Опубликовать: git push origin main --follow-tags"
