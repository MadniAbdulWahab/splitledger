#!/usr/bin/env bash
# Builds the API image and pushes it to the ECR repository created by infra/app, tagged with the
# current git commit. Prints the tag, which is then passed to Terraform as api_image_tag.
#
# Usage: scripts/push-image.sh      (needs docker, git, terraform and a valid `aws login` session)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Native path for Windows programs (terraform.exe, docker.exe) when running in Git Bash.
native_path() { if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
INFRA="$(native_path "$ROOT/infra/app")"
REGION="${AWS_REGION:-eu-central-1}"

if [ -n "$(git -C "$ROOT" status --porcelain -- api web)" ]; then
  echo "api/ or web/ has uncommitted changes; commit them first so the image tag matches the code." >&2
  exit 1
fi

REPOSITORY="$(terraform -chdir="$INFRA" output -raw ecr_repository_url)"
TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
REGISTRY="${REPOSITORY%%/*}"

# Fargate runs X86_64 (see service.tf); build for it even on an ARM laptop.
docker build --platform linux/amd64 --file "$(native_path "$ROOT/api/Dockerfile")" --tag "$REPOSITORY:$TAG"   "$(native_path "$ROOT")" >&2

# The ECR password is a 12-hour token derived from the current AWS login. It is too long for the
# Windows Credential Manager ("The stub received bad data"), so login and push use a throwaway
# Docker config that is deleted when the script ends. DOCKER_HOST keeps the current engine.
# docker.exe on Windows (Git Bash's "docker" is a wrapper that needs the PATH), docker elsewhere.
DOCKER="$(command -v docker.exe || command -v docker)"
DOCKER_HOST="$("$DOCKER" context inspect --format '{{.Endpoints.docker.Host}}')"
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_HOST DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT

# On Windows, Docker picks the Credential Manager helper whenever docker-credential-wincred is on
# the PATH, even with an empty config. Hiding Docker's own folder from the PATH (and calling docker
# by its full path) makes it store the token in the throwaway config file instead.
PUSH_PATH="$PATH"
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    DOCKER_DIR="$(dirname "$DOCKER")"
    PUSH_PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -vxF "$DOCKER_DIR" | paste -sd ':')"
    ;;
esac

aws ecr get-login-password --region "$REGION" \
  | PATH="$PUSH_PATH" "$DOCKER" login --username AWS --password-stdin "$REGISTRY" >&2
PATH="$PUSH_PATH" "$DOCKER" push "$REPOSITORY:$TAG" >&2

echo "$TAG"
