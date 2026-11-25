import java.time.Instant
import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test

def diagnosticDir = new File(System.getenv('FLARE_CI_DIAG'))

gradle.taskGraph.whenReady { graph ->
    def blocked = graph.allTasks.findAll { it.name ==~ /(?i)(publish.*|upload.*|bundleRelease)/ }
    if (!blocked.isEmpty()) {
        throw new GradleException('Preflight diagnostics encountered a publishing task: ' + blocked*.path.join(', '))
    }
}

gradle.allprojects {
    tasks.withType(Test).configureEach { task ->
        def eventLog = new File(diagnosticDir, task.path.replace(':', '_') + '.tsv')
        def record = { kind, name ->
            def cleanName = name.replaceAll(/[\t\r\n]/, ' ')
            def line = [Instant.now().epochSecond, kind, cleanName].join('\t') + '\n'
            eventLog.append(line, 'UTF-8')
        }

        testLogging {
            events 'started', 'passed', 'failed', 'skipped'
            exceptionFormat = 'full'
        }
        doFirst {
            record('task-started', task.path)
        }
        beforeTest { test ->
            record('test-started', test.className + '.' + test.name)
        }
        afterTest { test, result ->
            record(result.resultType.name().toLowerCase(Locale.ROOT), test.className + '.' + test.name)
            if (test.className.endsWith('.MangaReaderPresenterTest')) {
                result.exceptions.each { failure ->
                    eventLog.withWriterAppend('UTF-8') { writer ->
                        failure.printStackTrace(new PrintWriter(writer))
                    }
                }
            }
        }
        doLast {
            record('task-finished', task.path)
        }
    }
}
