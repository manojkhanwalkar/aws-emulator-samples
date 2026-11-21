#!/usr/bin/env bash
# Sample 13: the AWS CLI against AWS Emulator, configured only with environment variables.
#
# Sample 1 does this from Java with the Emulator helper. Other tools (the AWS CLI, Boto3, the Go and
# JavaScript SDKs, Terraform...) have no such helper: you point them at the emulator with the
# environment variables from the emulator guide ("Pointing your AWS SDK at AWS Emulator"), and your
# code or commands stay exactly as they are for AWS.
#
# Needs: a running emulator (`aws-emulator -t off`), AWS CLI v2 and curl.
# Run:   scripts/cli-sample.sh            (emulator on 9297)
#        AWS_EMULATOR_PORT=9300 scripts/cli-sample.sh
set -euo pipefail

PORT="${AWS_EMULATOR_PORT:-9297}"
DIR="$HOME/.aws/emulator/$PORT"

# 1. Point the CLI at the emulator. Set these in the same script that runs your commands or tests:
#    without AWS_ENDPOINT_URL, the CLI would call real AWS with whatever credentials it finds.
export AWS_ENDPOINT_URL="https://127.0.0.1:$PORT"          # every service, one endpoint (keep 127.0.0.1)
export AWS_SHARED_CREDENTIALS_FILE="$DIR/emulator_credentials" # this run's keys
export AWS_CONFIG_FILE="$DIR/config"                        # Region us-east-1, endpoint, CA bundle
export AWS_CA_BUNDLE="$DIR/ca.pem"                           # trust the emulator's own certificate
export AWS_EC2_METADATA_DISABLED=true                        # never pick up EC2 instance credentials
export AWS_DISABLE_HOST_PREFIX_INJECTION=true                # no "sync-127.0.0.1" style host names
export AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1
export AWS_PAGER=""                                          # print, don't page
# Environment keys win over the credentials file: unset them, or the CLI signs with your real keys
# (and the emulator refuses them).
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE
# Other settings that could send calls elsewhere or sign with other credentials: unset them too.
unset AWS_IGNORE_CONFIGURED_ENDPOINT_URLS AWS_WEB_IDENTITY_TOKEN_FILE AWS_ROLE_ARN AWS_ROLE_SESSION_NAME \
      AWS_CONTAINER_CREDENTIALS_FULL_URI AWS_CONTAINER_CREDENTIALS_RELATIVE_URI AWS_CONTAINER_AUTHORIZATION_TOKEN
for v in $(compgen -v AWS_ENDPOINT_URL_); do unset "$v"; done   # per-service endpoints override AWS_ENDPOINT_URL

echo "== 13. The AWS CLI with environment variables =="
if [[ ! -f "$DIR/ca.pem" ]]; then
  echo "No AWS Emulator on port $PORT (no $DIR/ca.pem). Start one first: aws-emulator -t off" >&2
  exit 1
fi

# 2. Wait for the health route, as a test script would (up to 30 seconds).
for _ in $(seq 30); do
  if curl -sf --cacert "$AWS_CA_BUNDLE" "$AWS_ENDPOINT_URL/__admin/health" >/dev/null; then break; fi
  sleep 1
done
HEALTH=$(curl -sf --cacert "$AWS_CA_BUNDLE" "$AWS_ENDPOINT_URL/__admin/health") || {
  echo "The emulator on port $PORT is not healthy after 30 seconds." >&2
  exit 1
}
echo "Health check: $HEALTH"

# 3. Who am I? The emulator always answers with account 000000000000.
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
echo "Account: $ACCOUNT   ARN: $(aws sts get-caller-identity --query Arn --output text)"
if [[ "$ACCOUNT" != "000000000000" ]]; then
  echo "Not the emulator's account: these commands are going somewhere else!" >&2
  exit 1
fi
echo "-> Account 000000000000: the CLI talks to the emulator, not AWS."

# 4. Ordinary CLI commands from here on: an S3 round trip.
BUCKET="samples-cli-$(date +%s)-$$"           # unique, lower case
TMP=$(mktemp -d)
# On any exit, including a failed step: remove the temp folder and the bucket (if it was made).
trap 'rm -rf "$TMP"; aws s3 rb "s3://$BUCKET" --force >/dev/null 2>&1 || true' EXIT
echo "hello from the AWS CLI" > "$TMP/note.txt"
aws s3api create-bucket --bucket "$BUCKET" >/dev/null
aws s3 cp "$TMP/note.txt" "s3://$BUCKET/note.txt" --only-show-errors
echo "Read back from s3://$BUCKET/note.txt: $(aws s3 cp "s3://$BUCKET/note.txt" - --only-show-errors)"
aws s3 rb "s3://$BUCKET" --force >/dev/null
echo "Deleted the bucket"
