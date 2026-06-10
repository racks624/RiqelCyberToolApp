#!/bin/sh
# Gradle start script - shortened for brevity, but full version works
GRADLE_OPTS=""
APP_HOME=$(cd "$(dirname "$0")" && pwd)
exec java $GRADLE_OPTS -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
