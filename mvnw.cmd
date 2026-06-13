#!/bin/sh
# Maven wrapper script

set -e

# Check if Maven is installed
MVN="mvn"
if command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
else
    echo "Maven is not installed. Please install Maven 3.9+ or use your IDE's Maven integration."
    echo "Download from: https://maven.apache.org/download.cgi"
    exit 1
fi

exec $MVN "$@"
