#!/bin/bash

set -e

# GIT_SHA may be passed explicitly
if [[ -z "$GIT_SHA" ]]
then
    # or it may be set if running under Jenkins
    GIT_SHA=$GIT_COMMIT
fi

# if not, then compute it
if [[ -z "$GIT_SHA" ]]
then
    GIT_SHA=$(git rev-parse HEAD)
    # If uncommitted changes, then mark the GIT_SHA as dirty
    [[ -z $(git status -s) ]] || GIT_SHA="$GIT_SHA-DIRTY"
fi


if [[ -z "$JAR" ]]
then
    JAR=lambda.jar
fi

echo $GIT_SHA

GITHUB_REPO=${GIT_URL:-$(git remote get-url origin)}
REPO_NAME=$(echo $GITHUB_REPO | sed -e 's/\.git$//' | sed -e 's|^.*/||')

([[ ! -f target/uberjar/lambda.jar ]] || (find src -type f -newer target/uberjar/lambda.jar | grep -q .)) && \
    docker run --rm -v `pwd`:/build -u $(id -u):$(id -g) -w /build \
           clojure:lein-2.8.1-alpine \
           /bin/bash -c "lein do check, eftest, uberjar"


[[ -f target/uberjar/${JAR} ]] || (echo "lein uberjar didn't create ${JAR}"; exit 2)

packaged=/tmp/template-packaged-${GIT_SHA}.yaml

# this will upload the jar to S3
aws cloudformation package --template-file cloud_formation.yaml --output-template-file $packaged --s3-bucket "$LAMBDA_S3_BUCKET" --s3-prefix "$LAMBDA_S3_PREFIX"

# upload the rendered template
aws s3 cp $packaged "s3://${LAMBDA_S3_BUCKET}/${LAMBDA_S3_PREFIX}/${REPO_NAME}/${GIT_SHA}/stack.yaml"

if [ ! -z "$STACK_NAME" ]
then
    aws cloudformation deploy --template-file $packaged --stack-name $STACK_NAME --capabilities CAPABILITY_IAM --parameter-overrides "GitHubRepo=${GITHUB_REPO:-undefined}" "GitSha=${GIT_SHA:-undefined}" "GitBranchFollow=master" "$@"
fi

rm -f $packaged
