package project.datasources.vcs.git;

import project.datasources.vcs.VersionControlSystem;
import project.model.metadata.MetadataType;
import project.model.Commit;
import project.model.File;
import utilis.external.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class Git implements VersionControlSystem {

    private static final String ISO_DATE = "--date=iso-strict";
    private final ExternalApplication gitApplication;


    public Git(String rootDirectoryPath, String workingDirectoryPath, String repositoryURL) {

        java.io.File workingDirectory = new java.io.File(workingDirectoryPath);

        if (!workingDirectory.isDirectory()) {

            ExternalApplication git = new ExternalApplication("git", rootDirectoryPath);
            git.execute("clone", "--quiet", repositoryURL, workingDirectoryPath);
        }

        this.gitApplication = new ExternalApplication("git", workingDirectoryPath);
    }

    @Override
    public Commit getCommitByTag(String tag) {

        OneLineReader gitOutputReader = new OneLineReader();

        this.gitApplication.execute(gitOutputReader, "show-ref", "-s", tag);

        if (gitOutputReader.getOutput() != null)

            return getCommitByHash(gitOutputReader.getOutput());

        else {

            ExternalApplicationOutputListMaker reader = new ExternalApplicationOutputListMaker();

            this.gitApplication.execute(reader, "tag");

            for (String outputTag : reader.output)
                if (outputTag.contains(tag))
                    return getCommitByTag(outputTag);

        }

        return null;
    }

    @Override
    public Commit getCommitByDate(LocalDateTime date) {

        GitCommitGetter gitOutputReader = new GitCommitGetter();

        this.gitApplication.execute(gitOutputReader, "log", ISO_DATE, "--before=" + date.toString(), "--max-count=1", "--pretty=format:\"%H<->%cd\"");

        return gitOutputReader.output;
    }

    @Override
    public Commit getCommitByLogMessagePattern(String pattern) {

        GitCommitGetter gitOutputReader = new GitCommitGetter();

        this.gitApplication.execute(gitOutputReader, "log", ISO_DATE, "--grep=\"" + pattern + "\"", "--max-count=1", "--pretty=format:\"%H<->%cd\"");

        return gitOutputReader.output;
    }

    @Override
    public Map<String, File> getFiles(String commitHash) {

        GitFilesGetter gitOutputReader = new GitFilesGetter();

        this.gitApplication.executeWithOutputRedirection(gitOutputReader, "ls-tree", "-r", commitHash);

        return gitOutputReader.output;
    }

    @Override
    public List<String> getFilesChangedByCommit(String commitHash) {

        ExternalApplicationOutputListMaker reader = new ExternalApplicationOutputListMaker();

        this.gitApplication.executeWithOutputRedirection(reader, "diff-tree", "--no-commit-id", "--name-only", "-r", commitHash);

        return reader.output;
    }

    @Override
    public void computeFileMetrics(File file, Commit releaseCommit) {

        computeChangeSetSizeAndNumberOfRevisions(file, releaseCommit);
        computeFileLOCMetric(file);
        computeLOCMetrics(file, releaseCommit);
        computeFileAgeMetrics(file, releaseCommit);
        computeNumberOfAuthorsOfFile(file, releaseCommit);
    }

    private void computeChangeSetSizeAndNumberOfRevisions(File file, Commit releaseCommit) {

        List<String> revisionsCommitHashList = this.getFileRevisionsHash((String) file.getMetadata(MetadataType.NAME), releaseCommit.hash);

        long maxChangeSetSize = 0;
        long averageChangeSetSize = 0;

        for (String revisionHash : revisionsCommitHashList) {

            long currentChangeSetSize = getChangeSetSize(revisionHash);

            if (maxChangeSetSize < currentChangeSetSize)
                maxChangeSetSize = currentChangeSetSize;

            averageChangeSetSize += currentChangeSetSize;
        }

        averageChangeSetSize /= revisionsCommitHashList.size();

        file.setMetadata(MetadataType.AVERAGE_CHANGE_SET_SIZE, averageChangeSetSize);
        file.setMetadata(MetadataType.MAX_CHANGE_SET_SIZE, maxChangeSetSize);
        file.setMetadata(MetadataType.NUMBER_OF_REVISIONS, revisionsCommitHashList.size());
    }

    public void computeFileLOCMetric(File file) {

        ExternalApplicationOutputLinesCounter gitOutputReader = new ExternalApplicationOutputLinesCounter();

        this.gitApplication.executeWithOutputRedirection(gitOutputReader, "cat-file", "-p", file.getHash());

        file.setMetadata(MetadataType.LOC, gitOutputReader.getOutput());
    }

    private Commit getCommitByHash(String commitHash) {

        ExternalApplicationOutputListMaker gitOutputReader = new ExternalApplicationOutputListMaker();

        this.gitApplication.execute(gitOutputReader, "show", ISO_DATE, "--format=\"%cd\"", "-s", commitHash);

        List<String> gitOutput = gitOutputReader.output;
        LocalDateTime commitLocalDateTime = LocalDateTime.parse(gitOutput.get(gitOutput.size() - 1), DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return new Commit(commitHash, commitLocalDateTime);
    }

    private List<String> getFileRevisionsHash(String filename, String releaseCommitHash) {

        ExternalApplicationOutputListMaker gitOutputReader = new ExternalApplicationOutputListMaker();

        this.gitApplication.executeWithOutputRedirection(gitOutputReader, "log", "--follow", "--format=%H", releaseCommitHash, "--", filename);

        return gitOutputReader.output;
    }

    private void computeFileAgeMetrics(File file, Commit releaseCommit) {

        ExternalApplicationOutputListMaker gitOutputReader = new ExternalApplicationOutputListMaker();

        this.gitApplication.execute(gitOutputReader, "log", releaseCommit.hash, ISO_DATE, "--pretty=format:\"%cd\"", "--", file.getName());

        String creationDateAsString = gitOutputReader.output.get(gitOutputReader.output.size() - 1);
        LocalDateTime creationDate = LocalDateTime.parse(creationDateAsString, DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        double ageInWeeks = Duration.between(creationDate, releaseCommit.date).toDays() / 7.0;

        file.setMetadata(MetadataType.AGE_IN_WEEKS, ageInWeeks);
        file.setMetadata(MetadataType.WEIGHTED_AGE_IN_WEEKS, ageInWeeks / (long) file.getMetadata(MetadataType.LOC_TOUCHED));
    }

    private void computeNumberOfAuthorsOfFile(File file, Commit releaseCommit) {

        ExternalApplicationOutputLinesCounter gitOutputReader = new ExternalApplicationOutputLinesCounter();

        this.gitApplication.execute(gitOutputReader, "shortlog", releaseCommit.hash, "-s", "--", file.getName());

        file.setMetadata(MetadataType.NUMBER_OF_AUTHORS, gitOutputReader.getOutput());
    }

    private void computeLOCMetrics(File file, Commit releaseCommit) {

        long addedCodeLines = 0;
        long removedCodeLines = 0;

        long maxChurn = 0;
        long maxLOCAdded = 0;

        ExternalApplicationOutputListMaker gitOutputReader = new ExternalApplicationOutputListMaker();

        this.gitApplication.executeWithOutputRedirection(gitOutputReader, "log", "--follow", "--pretty=format:'%H<->%cd'", "--stat", releaseCommit.hash, "--", file.getName());

        List<String> gitOutput = gitOutputReader.output;

        for (int index = gitOutput.size() - 1; index >= 0; index -= 4) {

            long insertions = 0;
            long deletions = 0;

            String[] outputContainingInsertionAndDeletion = gitOutput.get(index).split("\\s+");
            String[] outputContainingModifications = gitOutput.get(index - 1).split("\\s+");

            String modificationFlag = outputContainingModifications[outputContainingModifications.length - 1];

            if (modificationFlag.contains("-") && modificationFlag.contains("+")) {

                insertions = Long.parseLong(outputContainingInsertionAndDeletion[4]);
                deletions = Long.parseLong(outputContainingInsertionAndDeletion[6]);

            } else if (modificationFlag.contains("-"))

                deletions = Long.parseLong(outputContainingInsertionAndDeletion[4]);

            else if (modificationFlag.contains("+"))

                insertions = Long.parseLong(outputContainingInsertionAndDeletion[4]);

            addedCodeLines += insertions;
            removedCodeLines += deletions;

            if (maxChurn < (insertions - deletions))
                maxChurn = (insertions - deletions);

            if (maxLOCAdded < insertions)
                maxLOCAdded = insertions;
        }

        int numberOfRevision = (int) file.getMetadata(MetadataType.NUMBER_OF_REVISIONS);

        file.setMetadata(MetadataType.LOC_ADDED, addedCodeLines);
        file.setMetadata(MetadataType.MAX_LOC_ADDED, maxLOCAdded);
        file.setMetadata(MetadataType.AVERAGE_LOC_ADDED, addedCodeLines / numberOfRevision);
        file.setMetadata(MetadataType.LOC_TOUCHED, addedCodeLines + removedCodeLines);

        file.setMetadata(MetadataType.CHURN, addedCodeLines - removedCodeLines);
        file.setMetadata(MetadataType.MAX_CHURN, maxChurn);
        file.setMetadata(MetadataType.AVERAGE_CHURN, (addedCodeLines - removedCodeLines) / numberOfRevision);
    }

    private long getChangeSetSize(String commitHash) {

        ExternalApplicationOutputLinesCounter gitOutputReader = new ExternalApplicationOutputLinesCounter();

        this.gitApplication.executeWithOutputRedirection(gitOutputReader, "diff-tree", "--no-commit-id", "--name-only", "-r", commitHash);

        return gitOutputReader.getOutput() - 1;
    }
}