/*
 * Copyright (c) 2013-2014, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2014, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor
import java.nio.file.Path

import groovy.transform.InheritConstructors
import groovy.util.logging.Slf4j
import nextflow.processor.TaskRun
import nextflow.util.Duration

/**
 * Processor for SLURM resource manager (DRAFT)
 *
 * See http://computing.llnl.gov/linux/slurm/
 *
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@InheritConstructors
class SlurmExecutor extends AbstractGridExecutor {

    /**
     * Gets the directives to submit the specified task to the cluster for execution
     *
     * @param task A {@link TaskRun} to be submitted
     * @param result The {@link List} instance to which add the job directives
     * @return A {@link List} containing all directive tokens and values.
     */
    protected List<String> getDirectives(TaskRun task, List<String> result) {

        result << '-D' << task.workDir.toString()
        result << '-J' << getJobNameFor(task)
        result << '-o' << '/dev/null'

        if( taskConfig.cpus ) {
            result << '-c' << taskConfig.cpus.toString()
        }

        if( taskConfig.time ) {
            result << '-t' << (taskConfig.time as Duration).format('HH:mm:ss')
        }

        if( taskConfig.getMemory() ) {
            result << '--mem' << taskConfig.getMemory().toMega().toString()
        }

        // -- at the end append the command script wrapped file name
        if( taskConfig.clusterOptions ) {
            result.addAll( getClusterOptionsAsList() )
        }

        return result
    }


    /**
     * The command line to submit this job
     *
     * @param task The {@link TaskRun} instance to submit for execution to the cluster
     * @param scriptFile The file containing the job launcher script
     * @return A list representing the submit command line
     */
    @Override
    List<String> getSubmitCommandLine(TaskRun task, Path scriptFile ) {

        final result = ['sbatch']

        // -- adds the jobs directives to the command line
        getDirectives(task, result)

        // -- last entry to 'script' file name
        // replace with the 'shell' attribute
        result << scriptFile.getName()

    }

    /**
     * Parse the string returned by the {@code sbatch} command and extract the job ID string
     *
     * @param text The string returned when submitting the job
     * @return The actual job ID string
     */
    @Override
    def parseJobId(String text) {
        def pattern = ~ /Submitted batch job (\d+)/
        for( String line : text.readLines() ) {
            def m = pattern.matcher(line)
            if( m.matches() ) {
                return m[0][1].toString()
            }
        }

        throw new IllegalStateException("Invalid SLURM submit response:\n$text\n\n")
    }


    protected List<String> killTaskCommand(def jobId) {
        ['scancel', jobId?.toString() ]
    }

    @Override
    protected List<String> queueStatusCommand(Object queue) {
        if( queue )
            log.debug "SLURM executor does not support queue parameter on queue status"

        return ['squeue','-h','-o','%i %t']
    }

    static private Map STATUS_MAP = [
            'PD': QueueStatus.PENDING,  // (pending)
            'R': QueueStatus.RUNNING,   // (running)
            'CA': QueueStatus.ERROR,    // (cancelled)
            'CF': QueueStatus.PENDING,  // (configuring)
            'CG': QueueStatus.RUNNING,  // (completing)
            'CD': QueueStatus.DONE,     // (completed)
            'F': QueueStatus.ERROR,     // (failed),
            'TO': QueueStatus.ERROR,    // (timeout),
            'NF': QueueStatus.ERROR     // (node failure)
    ]

    @Override
    protected Map<?, QueueStatus> parseQueueStatus(String text) {

        def result = [:]

        text.eachLine { String line ->
            def cols = line.split(/\s+/)
            if( cols.size() == 2 ) {
                result.put( cols[0], STATUS_MAP.get(cols[1]) )
            }
        }

        return result
    }
}