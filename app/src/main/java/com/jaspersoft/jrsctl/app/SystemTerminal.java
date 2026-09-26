package com.jaspersoft.jrsctl.app;

import java.io.IOException;
import java.util.Optional;
import org.jline.terminal.TerminalBuilder;

/**
 * The one JLine terminal of this process, shared by the guided menu's line editing ({@link
 * Prompter}) and the pager ({@link Pager}). Invariants: built at most once, from the JNI provider
 * only (no JNA, FFM, Jansi or exec provider, and never a dumb terminal; ADR-0037); never rebuilt
 * once building it has failed; empty when there is no console at all, since a piped or redirected
 * session gets nothing from either feature; closed by a shutdown hook.
 */
final class SystemTerminal {

  private static volatile boolean unavailable = false;
  private static volatile Optional<org.jline.terminal.Terminal> terminal = Optional.empty();

  private SystemTerminal() {}

  static synchronized Optional<org.jline.terminal.Terminal> get() {
    if (unavailable || !Terminal.present()) {
      return Optional.empty();
    }
    if (terminal.isPresent()) {
      return terminal;
    }
    try {
      org.jline.terminal.Terminal built =
          TerminalBuilder.builder()
              .system(true)
              .provider("jni")
              .jni(true)
              .ffm(false)
              .jna(false)
              .jansi(false)
              .exec(false)
              .dumb(false)
              .build();
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      built.close();
                    } catch (IOException ignored) {
                      // best effort: the process is exiting either way
                    }
                  },
                  "jrsctl-jline-close"));
      terminal = Optional.of(built);
    } catch (Throwable e) {
      unavailable = true;
      terminal = Optional.empty();
    }
    return terminal;
  }
}
