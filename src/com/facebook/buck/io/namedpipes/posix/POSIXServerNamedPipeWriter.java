/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io.namedpipes.posix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * POSIX name pipe writer reader that creates and removes a physical file corresponding to the named
 * pipe.
 */
class POSIXServerNamedPipeWriter extends POSIXClientNamedPipeWriter {

  POSIXServerNamedPipeWriter(Path path) {
    super(path);
  }

  @Override
  public void close() throws IOException {
    super.close();
    Files.deleteIfExists(getPath());
  }
}
