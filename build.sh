#!/usr/bin/env bash
# 构建插件。注意：jar 内只包含占位配置，绝不包含任何密钥。
# 密钥请在部署后写入服务器的 plugins/CargoWhitelist/config.yml（可从 config.local.yml 拷贝）。
# 用法：./build.sh
set -euo pipefail
cd "$(dirname "$0")"

mvn -B clean package -DskipTests

JAR=$(ls target/CargoWhitelist-*.jar | grep -v 'original-' | head -n1)
echo "构建完成：$JAR"
echo "（jar 内 config.yml 为占位配置，不含密钥）"
