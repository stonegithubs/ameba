package ameba.mvc.template.internal;

import ameba.core.Frameworks;
import ameba.mvc.ErrorPageGenerator;
import ameba.mvc.template.TemplateException;
import ameba.mvc.template.TemplateNotFoundException;
import ameba.util.IOUtils;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.internal.util.PropertiesHelper;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.internal.util.collection.DataStructures;
import org.glassfish.jersey.internal.util.collection.Value;
import org.glassfish.jersey.message.MessageBodyWorkers;
import org.glassfish.jersey.server.mvc.Viewable;
import org.glassfish.jersey.server.mvc.internal.LocalizationMessages;
import org.glassfish.jersey.server.mvc.internal.TemplateHelper;
import org.glassfish.jersey.server.mvc.spi.TemplateProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletContext;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import java.io.*;
import java.lang.annotation.Annotation;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * @author icode
 */
@Provider
@Singleton
public abstract class AmebaTemplateProcessor<T> implements TemplateProcessor<T> {
    public static final String INNER_VIEW_DIR = "/__views/ameba/";
    private static Logger logger = LoggerFactory.getLogger(AmebaTemplateProcessor.class);
    private final ConcurrentMap<String, T> cache;
    private final String suffix;
    private final Configuration config;
    private final ServletContext servletContext;
    private final String basePath;
    private final Charset encoding;
    /**
     * Create an instance of the processor with injected {@link javax.ws.rs.core.Configuration config} and
     * (optional) {@link javax.servlet.ServletContext servlet context}.
     *
     * @param config              configuration to configure this processor from.
     * @param servletContext      (optional) servlet context to obtain template resources from.
     * @param propertySuffix      suffix to distinguish properties for current template processor.
     * @param supportedExtensions supported template file extensions.
     */

    Set<String> supportedExtensions;
    @Context
    private MessageBodyWorkers workers;
    @Inject
    private ServiceLocator serviceLocator;
    private MessageBodyWriter<Viewable> viewableMessageBodyWriter;
    private ErrorPageGenerator errorPageGenerator;

    public AmebaTemplateProcessor(Configuration config, ServletContext servletContext, String propertySuffix, String... supportedExtensions) {
        this.config = config;
        this.suffix = '.' + propertySuffix;
        this.servletContext = servletContext;
        Map properties = config.getProperties();
        String basePath = PropertiesHelper.getValue(properties, "jersey.config.server.mvc.templateBasePath" + this.suffix, String.class, null);
        if (basePath == null) {
            basePath = PropertiesHelper.getValue(properties, "jersey.config.server.mvc.templateBasePath", "", null);
        }

        this.basePath = basePath;
        Boolean cacheEnabled = PropertiesHelper.getValue(properties, "jersey.config.server.mvc.caching" + this.suffix, Boolean.class, null);
        if (cacheEnabled == null) {
            cacheEnabled = PropertiesHelper.getValue(properties, "jersey.config.server.mvc.caching", false, null);
        }

        this.cache = cacheEnabled ? DataStructures.<String, T>createConcurrentMap() : null;
        this.encoding = TemplateHelper.getTemplateOutputEncoding(config, this.suffix);

        this.supportedExtensions = Sets.newHashSet(Collections2.transform(
                Arrays.asList(supportedExtensions), new Function<String, String>() {
                    @Override
                    public String apply(String input) {
                        input = input.toLowerCase();
                        return input.startsWith(".") ? input : "." + input;
                    }
                }));

    }

    protected String getBasePath() {
        return this.basePath;
    }

    protected ServletContext getServletContext() {
        return this.servletContext;
    }

    public MessageBodyWriter<Viewable> getViewableMessageBodyWriter() {
        if (viewableMessageBodyWriter == null)
            synchronized (this) {
                if (viewableMessageBodyWriter == null) {
                    viewableMessageBodyWriter = Frameworks.getViewableMessageBodyWriter(workers);
                }
            }
        return viewableMessageBodyWriter;
    }

    protected ErrorPageGenerator getErrorPageGenerator() {
        if (errorPageGenerator == null)
            synchronized (this) {
                if (errorPageGenerator == null) {
                    this.errorPageGenerator = Frameworks.getErrorPageGenerator(serviceLocator);
                }
            }
        return errorPageGenerator;
    }


    private Collection<String> getTemplatePaths(String name) {
        String lowerName = name.toLowerCase();
        String templatePath = name;
        if (!templatePath.startsWith(INNER_VIEW_DIR)) {
            templatePath = this.basePath.endsWith("/") ? this.basePath + name.substring(1) : this.basePath + name;
        }
        Iterator var4 = this.supportedExtensions.iterator();

        String extension;
        do {
            if (!var4.hasNext()) {
                final String finalTemplatePath = templatePath;
                return Collections2.transform(this.supportedExtensions, new Function<String, String>() {
                    public String apply(String input) {
                        return finalTemplatePath + input;
                    }
                });
            }

            extension = (String) var4.next();
        } while (!lowerName.endsWith(extension));

        return Collections.singleton(templatePath);
    }

    protected <F> F getTemplateObjectFactory(ServiceLocator serviceLocator, Class<F> type, Value<F> defaultValue) {
        Object objectFactoryProperty = this.config.getProperty("jersey.config.server.mvc.factory" + this.suffix);
        if (objectFactoryProperty != null) {
            if (type.isAssignableFrom(objectFactoryProperty.getClass())) {
                return type.cast(objectFactoryProperty);
            }

            Class factoryClass = null;
            if (objectFactoryProperty instanceof String) {
                factoryClass = (Class) ReflectionHelper.classForNamePA((String) objectFactoryProperty).run();
            } else if (objectFactoryProperty instanceof Class) {
                factoryClass = (Class) objectFactoryProperty;
            }

            if (factoryClass != null) {
                if (type.isAssignableFrom(factoryClass)) {
                    return type.cast(serviceLocator.create(factoryClass));
                }

                logger.warn(LocalizationMessages.WRONG_TEMPLATE_OBJECT_FACTORY(factoryClass, type));
            }
        }

        return defaultValue.get();
    }

    protected Charset setContentType(MediaType mediaType, MultivaluedMap<String, Object> httpHeaders) {
        String charset = mediaType.getParameters().get("charset");
        Charset encoding;
        MediaType finalMediaType;
        if (charset == null) {
            encoding = this.getEncoding();
            HashMap typeList = new HashMap(mediaType.getParameters());
            typeList.put("charset", encoding.name());
            finalMediaType = new MediaType(mediaType.getType(), mediaType.getSubtype(), typeList);
        } else {
            encoding = Charset.forName(charset);
            finalMediaType = mediaType;
        }

        ArrayList typeList1 = new ArrayList(1);
        typeList1.add(finalMediaType.toString());
        httpHeaders.put("Content-Type", typeList1);
        return encoding;
    }

    protected Charset getEncoding() {
        return this.encoding;
    }

    private T _resolve(String name) {
        Iterator var2 = this.getTemplatePaths(name).iterator();

        while (true) {
            String template;
            InputStreamReader reader;
            do {
                if (!var2.hasNext()) {
                    return null;
                }

                template = (String) var2.next();
                reader = null;
                InputStream e;
                if (this.servletContext != null) {
                    e = this.servletContext.getResourceAsStream(template);
                    reader = e != null ? new InputStreamReader(e) : null;
                }

                if (reader == null) {
                    e = this.getClass().getResourceAsStream(template);
                    if (e == null) {
                        e = this.getClass().getClassLoader().getResourceAsStream(template);
                    }

                    reader = e != null ? new InputStreamReader(e) : null;
                }

                if (reader == null) {
                    try {
                        reader = new InputStreamReader(new FileInputStream(template), this.encoding);
                    } catch (FileNotFoundException var16) {
                        //no op
                    }
                }
            } while (reader == null);

            try {
                return this.resolve(template, reader);
            } catch (Exception e) {
                logger.warn(LocalizationMessages.TEMPLATE_RESOLVE_ERROR(template), e);
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new TemplateException("find template error: " + template, e, e.getStackTrace()[0].getLineNumber());
                }
            } finally {
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.warn(LocalizationMessages.TEMPLATE_ERROR_CLOSING_READER(), e);
                }

            }
        }
    }


    @Override
    public T resolve(String name, MediaType mediaType) {
        if (this.cache != null) {
            if (!this.cache.containsKey(name)) {
                this.cache.putIfAbsent(name, this._resolve(name));
            }

            return this.cache.get(name);
        } else {
            return this._resolve(name);
        }
    }


    protected abstract TemplateException createException(ParseException e);

    protected T resolve(String templatePath, Reader reader) throws Exception {
        try {
            if (templatePath != null && templatePath.startsWith(this.basePath))
                return _resolve(templatePath);
            else
                return resolve(reader);
        } catch (Exception e) {
            RuntimeException r;
            if (e instanceof ParseException) {
                r = createException((ParseException) e);
            } else if (e instanceof IllegalStateException) {
                return null;
            } else {
                r = new TemplateException("resolve template error: " + templatePath, e, e.getStackTrace()[0].getLineNumber());
            }
            throw r;
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    protected abstract T resolve(String templatePath) throws Exception;

    protected abstract T resolve(Reader reader) throws Exception;

    @Override
    public void writeTo(T templateReference, Viewable viewable, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream out) throws IOException {
        try {
            writeTemplate(templateReference, viewable, mediaType, httpHeaders, out);
        } catch (Exception e) {
            RuntimeException r;
            if (e instanceof ParseException) {
                r = createException((ParseException) e);
            } else {
                String file = getTemplateFile(templateReference);
                file = getBasePath() + file;
                File tFile = new File(file);
                String source = IOUtils.readFromResource(file);

                List<String> sources;

                if (!"".equals(source)) {
                    sources = Lists.newArrayList(source.split("\n"));
                } else {
                    sources = Lists.newArrayList();
                }

                if (e instanceof FileNotFoundException || e.getCause() instanceof FileNotFoundException) {
                    r = new TemplateNotFoundException(e.getMessage(),
                            e, -1, tFile, sources, -1);
                } else {
                    r = new TemplateException("Write template error in  " + file + ". " + e.getMessage(),
                            e, -1, tFile, sources, -1);
                }
            }

            viewable = (Viewable) getErrorPageGenerator().toResponse(r).getEntity();

            getViewableMessageBodyWriter().writeTo(viewable,
                    Viewable.class, Viewable.class, new Annotation[]{},
                    mediaType, httpHeaders,
                    out);
        }
    }

    public abstract String getTemplateFile(T templateReference);

    public abstract void writeTemplate(T templateReference, Viewable viewable, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream out) throws Exception;
}
