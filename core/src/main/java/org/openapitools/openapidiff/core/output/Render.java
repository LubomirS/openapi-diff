package org.openapitools.openapidiff.core.output;

import java.io.FileWriter;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;

public interface Render {

  String render(ChangedOpenApi diff);

  void render(ChangedOpenApi diff, String outPutFile);
}
