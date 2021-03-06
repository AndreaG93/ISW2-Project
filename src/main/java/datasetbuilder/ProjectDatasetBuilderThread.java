package datasetbuilder;

import datasetbuilder.datasources.vcs.VersionControlSystem;
import entities.Commit;
import entities.File;

import java.util.Queue;

public class ProjectDatasetBuilderThread implements Runnable {

    private final Queue<File> waitFreeQueue;
    private final VersionControlSystem versionControlSystem;
    private final Commit commit;

    public ProjectDatasetBuilderThread(Queue<File> waitFreeQueue, VersionControlSystem versionControlSystem, Commit commit) {
        this.waitFreeQueue = waitFreeQueue;
        this.versionControlSystem = versionControlSystem;
        this.commit = commit;
    }

    @Override
    public void run() {

        for (File currentFile = this.waitFreeQueue.poll(); currentFile != null; currentFile = this.waitFreeQueue.poll())
            this.versionControlSystem.computeFileMetrics(currentFile, this.commit);
    }
}