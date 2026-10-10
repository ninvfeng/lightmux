#!/usr/bin/env bash
# 落地页部署到 gz3（https://lightmux.lighttools.net）。纯静态，改完手动跑一次：site/deploy.sh
#
# 站点本身（域名、*.lighttools.net 证书、WAF 登记）是在 1Panel 里建的，配置文件归 1Panel 管，
# 这里只同步文件；/releases.json 的转发见 releases.conf。
# images 是指向 ../docs/images 的软链，rsync -L 传真实文件，截图只在仓库里存一份。
set -euo pipefail
cd "$(dirname "$0")"
rsync -azL --delete --exclude deploy.sh --exclude releases.conf ./ \
  gz3:/data/1panel/www/sites/lightmux.lighttools.net/index/
echo "https://lightmux.lighttools.net"
