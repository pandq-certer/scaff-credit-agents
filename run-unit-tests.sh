#!/bin/bash
# =============================================================
# 原子 Service 单元测试一键执行脚本
# 用法:
#   ./run-unit-tests.sh            # 跑全部已接入测试的模块
#   ./run-unit-tests.sh ocr-agent  # 只跑指定模块
# 环境: JDK21（Maven 默认 JDK25 会导致 Lombok 兼容问题）
# =============================================================
set -e
cd "$(dirname "$0")"

export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# 已接入单测的模块（首批基建验证通过）
TEST_MODULES=(
  repayment-plan-agent
  metric-calc-agent
  ocr-agent
  doc-parse-agent
  credit-report-agent
)

if [ $# -ge 1 ]; then
  # 指定模块
  mvn -pl "$1" test -Dtest='*Test' 2>&1 | tee /tmp/scaff-logs/unit-test-$(basename "$1").log \
    | grep -E "Tests run:|BUILD" || true
else
  # 全部模块
  mvn -pl "$(IFS=,; echo "${TEST_MODULES[*]}")" test 2>&1 \
    | tee /tmp/scaff-logs/unit-tests-all.log \
    | grep -E "Tests run:|BUILD" || true
fi
