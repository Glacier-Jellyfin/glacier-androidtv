# ClusterFuzzLite image for the :fuzz module: JDK and Jazzer, no Android SDK.
# Pinned by digest so the build only changes with a reviewed update.
FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1@sha256:bd13a763de1a0fb22099442094e7e0049657a8c47f01e9d7af8bb849e0ad4699
COPY . $SRC/glacier
WORKDIR $SRC/glacier
COPY .clusterfuzzlite/build.sh $SRC/
