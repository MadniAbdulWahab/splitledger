#!/usr/bin/env bash
# Builds the API image and pushes it to the ECR repository created by infra/app, tagged with the
# current git commit. Prints the tag, which is then passed to Terraform as api_image_tag.
#
# Usage: scripts/push-image.sh      (needs docker, git, terraform and a valid `aws login` session)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGION="${AWS_REGION:-eu-central-1}"

if [ -n "$(git -C "$ROOT" status --porcelain -- api)" ]; then
  echo "api/ has uncommitted changes; commit them first so the image tag matches the code." >&2
  exit 1
fi

REPOSITORY="$(terraform -chdir="$ROOT/infra/app" output -raw ecr_repository_url)"
TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
REGISTRY="${REPOSITORY%%/*}"

# Fargate runs X86_64 (see service.tf); build for it even on an ARM laptop.
docker build --platform linux/amd64 --tag "$REPOSITORY:$TAG" "$ROOT/api" >&2

# The ECR password is a 12-hour token derived from the current AWS login, not a stored secret.
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY" >&2
docker push "$REPOSITORY:$TAG" >&2

echo "$TAG"
