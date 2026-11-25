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
            if (task.path == ':shared:jvmTest' && System.getenv('FLARE_CI_COROUTINES') == 'true') {
                def agent = task.classpath.files.find { it.name == 'kotlinx-coroutines-core-jvm-1.11.0.jar' }
                if (!agent) {
                    throw new GradleException('Coroutine probe agent is missing from the test classpath')
                }
                task.jvmArgs('-javaagent:' + agent.absolutePath)
                task.systemProperty('kotlinx.coroutines.debug.enable.creation.stack.trace', 'false')
            }
            record('task-started', task.path)
        }
        beforeTest { test ->
            record('test-started', test.className + '.' + test.name)
        }
        afterTest { test, result ->
            record(result.resultType.name().toLowerCase(Locale.ROOT), test.className + '.' + test.name)
        }
        doLast {
            record('task-finished', task.path)
        }
    }
}
