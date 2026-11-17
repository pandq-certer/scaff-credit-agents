#!/bin/bash
# 路由回归集：已知误路由/边界案例固化，每次改动路由相关代码（LlmSceneRouter / SceneDialogSpecs.routerDesc /
# SceneScripts.keywords / SceneLlmDialogService 路由 prompt）后必须全量跑一遍，全部 PASS 才能提交/重启交付。
#
# 用法：./route-regression.sh [base_url]
#   默认 base_url=http://localhost:8201
# 退出码：0=全部通过；1=存在 FAIL（CI/交付前检查可直接用退出码）
#
# 说明：
# - 每条用例用独立 userId，避免会话上下文互相影响
# - S2-4/S9-2 同关键词抢占为既有路由特性，不在本回归集内
BASE_URL="${1:-http://localhost:8201}"
PASS=0; FAIL=0

# 用例集：问句|期望场景编号（换话术改写与原文问句都保留，防止只对字面优化）
CASES=(
  "存量客户里哪些有提额和复贷空间|S1-1"
  "帮我盘点一下存量客户里有哪些营销机会|S1-1"
  "下季度存量客户的复贷空间摸个底|S1-1"
  "怎么把睡眠户唤醒激活|S1-4"
  "帮我做存量休眠客户的激活方案|S1-4"
  "这笔贷款合规吗|S4-6"
  "帮我做个合规审查|S4-6"
  "这笔业务是不是踩红线了，能不能例外审批|S12-2"
  "红线检查过一遍|S12-2"
  "看下这个客户的完整画像|S11-3"
  "调出客户的客户画像看看|S1-8"
  "帮我盘一盘还能再借点钱的存量客户|S1-1"
  "假如想给老客户提升额度做一次筛查该从哪入手|S1-1"
  "找一找沉睡着但还可以继续合作的客户试试盘活|S1-4"
)

health=$(curl -s -m 5 "$BASE_URL/api/scene/health")
if [[ "$health" != *"UP"* && "$health" != *"ok"* && -z "$health" ]]; then
  echo "scene-bff 不可达：$BASE_URL —— 请先启动服务"; exit 2
fi

echo "路由回归集（$BASE_URL）"
printf "%-4s %-42s %-8s %-8s %s\n" "结果" "问句" "期望" "实际" "路由方式"
echo "------------------------------------------------------------------------------------------"
for c in "${CASES[@]}"; do
  q="${c%%|*}"; expect="${c##*|}"
  uid="regress-$(date +%s%N)-$RANDOM"
  resp=$(curl -s -m 60 -X POST "$BASE_URL/api/scene/chat" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$uid\",\"message\":\"$q\"}")
  actual=$(echo "$resp" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('sceneId') or 'null')" 2>/dev/null)
  reply=$(echo "$resp" | python3 -c "import sys,json;d=json.load(sys.stdin);print(((d.get('data') or {}).get('reply') or '')[:14])" 2>/dev/null)
  if [[ "$actual" == "$expect" ]]; then
    printf "%-4s %-42s %-8s %-8s %s\n" "PASS" "$q" "$expect" "$actual" "$reply"; PASS=$((PASS+1))
  else
    printf "%-4s %-42s %-8s %-8s %s\n" "FAIL" "$q" "$expect" "$actual" "$reply"; FAIL=$((FAIL+1))
  fi
done
echo "------------------------------------------------------------------------------------------"
echo "合计：PASS=$PASS FAIL=$FAIL"
[[ $FAIL -eq 0 ]]
