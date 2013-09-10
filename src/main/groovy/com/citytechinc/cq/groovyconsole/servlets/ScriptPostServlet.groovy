package com.citytechinc.cq.groovyconsole.servlets

import com.citytechinc.cq.groovy.extension.builders.NodeBuilder
import com.citytechinc.cq.groovy.extension.builders.PageBuilder
import com.citytechinc.cq.groovy.extension.services.OsgiComponentService
import com.citytechinc.cq.groovyconsole.services.GroovyConsoleConfigurationService
import com.day.cq.mailer.MailService
import com.day.cq.replication.ReplicationActionType
import com.day.cq.replication.Replicator
import com.day.cq.wcm.api.PageManager
import groovy.json.JsonBuilder
import org.apache.commons.mail.HtmlEmail
import org.apache.felix.scr.annotations.Activate
import org.apache.felix.scr.annotations.Deactivate
import org.apache.felix.scr.annotations.Reference
import org.apache.felix.scr.annotations.ReferenceCardinality
import org.apache.felix.scr.annotations.sling.SlingServlet
import org.apache.sling.api.SlingHttpServletRequest
import org.apache.sling.api.SlingHttpServletResponse
import org.apache.sling.api.resource.ResourceResolverFactory
import org.apache.sling.jcr.api.SlingRepository
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.runtime.StackTraceUtils
import org.osgi.framework.BundleContext
import org.slf4j.LoggerFactory

import javax.servlet.ServletException

@SlingServlet(paths = "/bin/groovyconsole/post")
class ScriptPostServlet extends AbstractScriptServlet {

    static final long serialVersionUID = 1L

    protected static final def SCRIPT_PARAM = "script"

    static final def LOG = LoggerFactory.getLogger(ScriptPostServlet)

    private static final def RUNNING_TIME = { closure ->
        def start = System.currentTimeMillis()

        closure()

        def date = new Date()

        date.setTime(System.currentTimeMillis() - start)
        date.format("HH:mm:ss.SSS", TimeZone.getTimeZone("GMT"))
    }

    @Reference
    SlingRepository repository

    @Reference
    Replicator replicator

    @Reference
    OsgiComponentService componentService

    @Reference
    ResourceResolverFactory resourceResolverFactory

    @Reference
    GroovyConsoleConfigurationService configurationService

    @Reference(cardinality = ReferenceCardinality.OPTIONAL_UNARY)
    MailService emailService

    def session

    def resourceResolver

    def pageManager

    def bundleContext

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws
        ServletException, IOException {
        def stream = new ByteArrayOutputStream()
        def binding = createBinding(request, stream)
        def shell = new GroovyShell(binding)

        def stackTrace = new StringWriter()
        def errorWriter = new PrintWriter(stackTrace)

        def result = ""
        def runningTime = ""
        def output = ""
        def error = ""

        def scriptContent = request.getRequestParameter(SCRIPT_PARAM)?.getString(ENCODING)

        try {
            def script = shell.parse(scriptContent)

            addMetaClass(script)

            runningTime = RUNNING_TIME {
                result = script.run()
            }

            LOG.debug "doPost() script execution completed, running time = $runningTime"

            output = stream.toString(ENCODING);

            saveOutput(output)

            sendEmailNotification(scriptContent, output, runningTime)
        } catch (MultipleCompilationErrorsException e) {
            LOG.error("script compilation error", e)

            StackTraceUtils.printSanitizedStackTrace(e, errorWriter)
        } catch (Throwable t) {
            LOG.error("error running script", t)

            StackTraceUtils.printSanitizedStackTrace(t, errorWriter)

            error = stackTrace.toString()

            sendEmailNotification(scriptContent, error, runningTime)
        } finally {
            stream.close()
            errorWriter.close()
        }

        response.contentType = "application/json"

        new JsonBuilder([
            executionResult: result as String,
            outputText: output,
            stacktraceText: error,
            runningTime: runningTime
        ]).writeTo(response.writer)
    }

    def createBinding(request, stream) {
        def printStream = new PrintStream(stream, true, ENCODING)

        new Binding([
            out: printStream,
            log: LoggerFactory.getLogger("groovyconsole"),
            session: session,
            slingRequest: request,
            pageManager: pageManager,
            resourceResolver: resourceResolver,
            nodeBuilder: new NodeBuilder(session),
            pageBuilder: new PageBuilder(session)
        ])
    }

    def addMetaClass(script) {
        script.metaClass {
            delegate.getNode = { path ->
                session.getNode(path)
            }

            delegate.getResource = { path ->
                resourceResolver.getResource(path)
            }

            delegate.getPage = { path ->
                pageManager.getPage(path)
            }

            delegate.move = { src ->
                ["to": { dst ->
                    session.move(src, dst)
                    session.save()
                }]
            }

            delegate.copy = { src ->
                ["to": { dst ->
                    session.workspace.copy(src, dst)
                }]
            }

            delegate.save = {
                session.save()
            }

            delegate.getService = { serviceType ->
                def ref = bundleContext.getServiceReference(serviceType)

                bundleContext.getService(ref)
            }

            delegate.activate = { path ->
                replicator.replicate(session, ReplicationActionType.ACTIVATE, path)
            }

            delegate.deactivate = { path ->
                replicator.replicate(session, ReplicationActionType.DEACTIVATE, path)
            }

            delegate.doWhileDisabled = { componentClassName, closure ->
                componentService.doWhileDisabled(componentClassName, closure)
            }
        }
    }

    @Activate
    void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext

        session = repository.loginAdministrative(null)
        resourceResolver = resourceResolverFactory.getAdministrativeResourceResolver(null)
        pageManager = resourceResolver.adaptTo(PageManager)
    }

    @Deactivate
    void deactivate() {
        session?.logout()
        resourceResolver?.close()
        pageManager = null
    }

    def sendEmailNotification(scriptContent, output, runningTime) {
        if (configurationService.emailEnabled && emailService) {
            def executedBy = session.userID
            def email = new HtmlEmail()

            email.setCharset(ENCODING)

            configurationService.emailRecipients.each { name ->
                email.addTo(name)
            }

            email.setSubject("CQ Groovy Console script execution result")

            // def message = "Groovy script was executed by <b>$executedBy</b> on <b>${new Date().format('dd-MM-yyyy hh:mm:ss')}</b>"

            email.setMsg("Groovy script was executed by <b>$executedBy</b> " +
                "on <b>${new Date().format('dd-MM-yyyy hh:mm:ss')}</b>\n\n" +
                "<h4>Script content</h4>\n<p>$scriptContent</p>\n\n" +
                "Execution time: $runningTime\n\n" +
                "<h4>Output</h4>\n${output ?: '<none>'}")

            emailService.send(email)
        }
    }

    def saveOutput(output) {
        if (configurationService.crxOutputEnabled) {
            def date = new Date()

            def folderPath = "${configurationService.crxOutputFolder}/${date.format('yyyy/MM/dd')}"
            def folderNode = session.rootNode.getOrAddNode(folderPath)

            def fileName = date.format('hhmmss')

            new ByteArrayInputStream(output.getBytes(ENCODING)).withStream { stream ->
                session.valueFactory.createBinary(stream).withBinary { binary ->
                    saveFile(session, folderNode, fileName, "text/plain", binary)
                }
            }
        }
    }
}
