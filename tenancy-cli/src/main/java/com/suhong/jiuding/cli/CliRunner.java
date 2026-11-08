/*
 * Copyright 2026 suhong
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
 *
 * @author suhong
 */
package com.suhong.jiuding.cli;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Builds and executes the picocli command line. Kept separate from
 * {@link TenancyCli#main(String[])} so tests can capture output and exit codes
 * without spawning a JVM.
 */
public final class CliRunner {

    public int execute(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        try {
            return execute(args, out, err);
        } finally {
            out.flush();
            err.flush();
        }
    }

    public int execute(String[] args, PrintWriter out, PrintWriter err) {
        TenancyCli root = new TenancyCli();
        CommandLine commandLine = new CommandLine(root);
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setUsageHelpAutoWidth(true);
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            int exitCode = ExitCodes.forThrowable(exception);
            String label = switch (exitCode) {
                case ExitCodes.USAGE -> "参数错误";
                case ExitCodes.AUTH -> "认证失败";
                case ExitCodes.BUSINESS -> "业务错误";
                case ExitCodes.NETWORK -> "网络错误";
                default -> "错误";
            };
            err.println("[" + label + "] " + describe(exception));
            err.flush();
            return exitCode;
        });
        commandLine.setParameterExceptionHandler((exception, originalArgs) -> {
            err.println("[参数错误] " + exception.getMessage());
            if (exception.getCommandLine() != null) {
                err.println();
                exception.getCommandLine().usage(err);
            }
            err.println();
            err.println("命令: tenancy " + String.join(" ", originalArgs == null ? new String[0] : originalArgs));
            err.flush();
            return ExitCodes.USAGE;
        });
        try {
            return commandLine.execute(args == null ? new String[0] : args);
        } catch (RuntimeException e) {
            err.println("[错误] " + describe(e));
            err.flush();
            return ExitCodes.forThrowable(e);
        }
    }

    private static String describe(Throwable throwable) {
        Throwable t = throwable;
        while (t != null) {
            if (t instanceof ApiException api && api.apiCode() > 0 && !t.getMessage().contains("HTTP")) {
                return t.getMessage() + " (code=" + api.apiCode() + ")";
            }
            if (t instanceof CliUsageException) {
                return t.getMessage();
            }
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getMessage();
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return "未知错误";
    }

    /** Convenience for manual smoke tests: prints exit code and args for debugging. */
    public static void main(String[] args) {
        int code = new CliRunner().execute(args);
        System.exit(code);
    }

    @Override
    public String toString() {
        return "CliRunner" + Arrays.toString(new String[0]);
    }
}
