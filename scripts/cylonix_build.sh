#! /bin/sh

# Go build ldflags does not work on local go module.
# The ldflags cause go to clone/copy the current repository
# to a /tmp location and git does not have spec for it:
#
# 	error: pathspec './tailscale' did not match any file(s) known to git
#
# Using -L flag to pass in local source directory or change go.mod to use
# absolute path for the local tailscale module does not help either.
# Hence we simply replace the version file in the source instead.

# Having arguments is to set the version strings.
ARGC=$#
if [ $ARGC -ne 0 ]; then
    TS_VERSION_FILE=../tailscale/version/version.go
    LONG_STAMP=$1
    SHORT_STAMP=$2
    EXTRA_GIT_COMMIT_STAMP=$3
    echo Setting version strings $LONG_STAMP $SHORT_STAMP $GIT_COMMIT_STAMP $EXTRA_GIT_COMMIT_STAMP...
    sed -i "s/longStamp string/longStamp = \"$LONG_STAMP\"/" $TS_VERSION_FILE
    sed -i "s/shortStamp string/shortStamp = \"$SHORT_STAMP\"/" $TS_VERSION_FILE
    sed -i "s/extraGitCommitStamp string/extraGitCommitStamp = \"$EXTRA_GIT_COMMIT_STAMP\"/" $TS_VERSION_FILE
    cd ../tailscale; git --no-pager diff version/version.go
else
    echo "Resetting version strings..."
    cd ../tailscale; git checkout version/version.go; git --no-pager diff version/version.go
fi
