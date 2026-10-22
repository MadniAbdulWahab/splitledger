#!/usr/bin/env bash
# Prints the URL of the running API task on AWS. There is no load balancer (it would cost about
# 20 USD/month), so the task's public IP changes with every deployment and is looked up here.
#
# Usage: BASE_URL="$(scripts/aws-api-url.sh)" scripts/demo.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLUSTER="$(terraform -chdir="$ROOT/infra/app" output -raw ecs_cluster_name)"
SERVICE="$(terraform -chdir="$ROOT/infra/app" output -raw ecs_service_name)"

TASK="$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$SERVICE" --desired-status RUNNING \
  --query 'taskArns[0]' --output text)"
if [ "$TASK" = "None" ]; then
  echo "No running task in $SERVICE (is api_desired_count 1?)" >&2
  exit 1
fi

ENI="$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" \
  --query "tasks[0].attachments[0].details[?name=='networkInterfaceId'].value | [0]" --output text)"
IP="$(aws ec2 describe-network-interfaces --network-interface-ids "$ENI" \
  --query 'NetworkInterfaces[0].Association.PublicIp' --output text)"

echo "http://$IP:8080"
