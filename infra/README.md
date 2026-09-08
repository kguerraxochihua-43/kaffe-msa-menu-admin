# CI image verification

This public repository runs the secure image workflow directly because GitHub
does not allow it to call the private infrastructure reusable workflow. Keep
action references pinned; the source template is documented in `build.yml`.

The existing `KaffeGitHubActionsDeployRole` already permits image publication.
Inline policy `KaffeMenuAdminImageScan` adds only starting and reading ECR scans
for this repository; it grants no other repository, deployment, data or secret
access. Its reviewed declaration is `ci-image-scan-policy.json`.

The primary runtime serves production even though the integration branch is
called `dev`. Deployment must use the immutable CI digest, a successful scan
with no critical or high findings, and the CI signature. Never deploy a local
image or treat the mutable `dev` tag as release evidence.
