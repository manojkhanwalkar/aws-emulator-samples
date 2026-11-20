# Security policy

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private vulnerability reporting instead
(*Security → Report a vulnerability* on this repository).

For a vulnerability in AWS Emulator itself, follow the instructions in the
[aws/aws-emulator](https://github.com/aws/aws-emulator) repository and on
[AWS vulnerability reporting](https://aws.amazon.com/security/vulnerability-reporting/).

## What these samples assume

- They are for local development and tests. AWS Emulator doesn't evaluate IAM policies the way AWS
  does, so don't use it to test security, and don't run it for production workloads.
- The emulator listens on `127.0.0.1` only. Its keys work only with the run that made them, and it
  deletes them when it stops.
- Never put real AWS credentials in this repository or in a sample. The samples sign with the
  emulator's own keys, and the emulator refuses real ones.
