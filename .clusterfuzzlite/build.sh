#!/bin/bash -eu
# Builds the fuzz targets of the :fuzz module for ClusterFuzzLite. fuzz/ is also a build of its
# own (fuzz/settings.gradle.kts), so Gradle never configures the Android modules here.
./gradlew --no-daemon -p fuzz jar fuzzRuntimeLibs

cp fuzz/build/libs/fuzz.jar "$OUT/"
cp fuzz/build/fuzz-libs/*.jar "$OUT/"
jars="fuzz.jar $(cd fuzz/build/fuzz-libs && ls -- *.jar | tr '\n' ' ')"
# The image brings Jazzer itself, the API included; the targets only compiled against it.
runtime_classpath=$(printf -- "\$this_dir/%s:" $jars)\$this_dir

seeds=fuzz/src/test/resources/io/github/glacier_jellyfin/androidtv/fuzz
for target in fuzz/src/main/kotlin/io/github/glacier_jellyfin/androidtv/fuzz/*Fuzzer.kt; do
  name=$(basename "$target" .kt)
  # LLVMFuzzerTestOneInput in the script is how ClusterFuzzLite tells fuzz targets from other files.
  cat > "$OUT/$name" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \
--cp=$runtime_classpath \
--target_class=io.github.glacier_jellyfin.androidtv.fuzz.$name \
--jvm_args="\$mem_settings" \
\$@
EOF
  chmod u+x "$OUT/$name"

  # The JUnit fuzz test's seeds (TracksFuzzTest's in TracksFuzzTestInputs/tracks), not its regressions.
  test=${name%Fuzzer}
  inputs="$seeds/${test}FuzzTestInputs/${test,}"
  if [ -d "$inputs" ]; then
    zip -j -q "$OUT/${name}_seed_corpus.zip" "$inputs"/*
  fi
done
