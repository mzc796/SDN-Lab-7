#!/bin/bash
# Delete all configured flow entries on a switch.
# Usage: ./del_all_flows.sh <switch_id>
# Example: ./del_all_flows.sh openflow:1

if [ -z "$1" ]; then
    echo "Usage: $0 <switch_id>"
    echo "Example: $0 openflow:1"
    exit 1
fi

SWITCH=$1
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Requesting config flows for $SWITCH ==="
bash "$SCRIPT_DIR/req_config_flows.sh" "$SWITCH"

CONFIG_FILE="$SCRIPT_DIR/data/${SWITCH}_config_flows.json"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Config file $CONFIG_FILE not found."
    exit 1
fi

# Check if the response contains an error (no flows)
if grep -q '"error-tag"' "$CONFIG_FILE" 2>/dev/null; then
    echo "No configured flows found on $SWITCH."
    exit 0
fi

# Extract flow IDs from the config JSON using python
FLOW_IDS=$(python3 -c "
import json, sys
with open('$CONFIG_FILE') as f:
    data = json.load(f)
table = data.get('flow-node-inventory:table', [{}])[0]
flows = table.get('flow', [])
for flow in flows:
    print(flow['id'])
" 2>/dev/null)

if [ -z "$FLOW_IDS" ]; then
    echo "No flows to delete on $SWITCH."
    exit 0
fi

TABLE=0
echo "=== Deleting all flows on $SWITCH (table $TABLE) ==="
while IFS= read -r FLOW_ID; do
    echo "Deleting flow $FLOW_ID ..."
    bash "$SCRIPT_DIR/del_flow.sh" "$SWITCH" "$TABLE" "$FLOW_ID"
    echo ""
done <<< "$FLOW_IDS"

echo "=== Done. All config flows on $SWITCH have been deleted. ==="
