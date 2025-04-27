#!/bin/bash

# This file should be sourced before running install
# source cloudshell.sh

set -e

echo "Installing Java 21 (Amazon Corretto)..."
sudo yum install -y java-21-amazon-corretto-devel

echo "Verifying Java Version..."
java -version

echo "Installing latest Maven manually..."

# Pick Maven version you want
MAVEN_VERSION=3.9.6
#
# Download and install
curl -fsSL https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz -o maven.tar.gz
sudo mkdir -p /opt/maven
sudo tar -xzvf maven.tar.gz -C /opt/maven --strip-components=1
rm maven.tar.gz
#
# Set up environment variables
export M2_HOME=/opt/maven
export MAVEN_HOME=/opt/maven
export PATH=$M2_HOME/bin:$PATH
#
echo "Verifying Maven Version..."
mvn -version
#
echo "Installing AWS CDK globally..."
sudo npm install -g aws-cdk
#
echo "Verifying CDK Version..."
cdk version
#
echo "✅ Environment ready. You can now run 'cd cdk' and 'mvn install' and 'cdk deploy ...'
