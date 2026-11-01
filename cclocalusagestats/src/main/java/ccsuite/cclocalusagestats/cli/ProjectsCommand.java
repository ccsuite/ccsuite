package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.report.Dimension;
import picocli.CommandLine.Command;

@Command(name = "projects", mixinStandardHelpOptions = true, description = "Tokens per project; see the filters below.")
public final class ProjectsCommand extends ReportBase {

    ProjectsCommand() {
        super(Dimension.PROJECTS);
    }
}
