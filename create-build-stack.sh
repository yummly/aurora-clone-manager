#!/bin/bash

set -e

GITHUB_REPO=${GIT_URL:-$(git remote get-url origin)}
REPO_NAME=$(echo $GITHUB_REPO | sed -e 's/\.git$//' | sed -e 's|^.*/||')

STACK_NAME=codebuild-${REPO_NAME}

aws cloudformation deploy --template-file build-stack.yml --stack-name "$STACK_NAME" --parameter-overrides "GitHubRepo=https://github.com/yummly/$REPO_NAME.git" "LambdaS3Bucket=$LAMBDA_S3_BUCKET" "LambdaS3Prefix=${LAMBDA_S3_PREFIX}" --capabilities CAPABILITY_IAM

# If you fork this repo and configure CodeBuild to use a GitHub user who has Admin privileges on your fork repo, then you can uncomment the next to line to create a webhook which will trigger CodeBuild upon push.

# PR=$(aws cloudformation describe-stacks --stack-name $STACK_NAME | jq -r '.Stacks[0].Outputs[] | .OutputKey + "," + .OutputValue' | grep '^BuildName,' | cut -f2 -d,)

# aws codebuild create-webhook --project-name $PR
