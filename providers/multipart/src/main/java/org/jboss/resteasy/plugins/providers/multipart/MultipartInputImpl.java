package org.jboss.resteasy.plugins.providers.multipart;

import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.MimeIOException;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.dom.*;
import org.apache.james.mime4j.dom.field.ContentTypeField;
import org.apache.james.mime4j.field.DefaultFieldParser;
import org.apache.james.mime4j.field.LenientFieldParser;
import org.apache.james.mime4j.message.*;
import org.apache.james.mime4j.parser.ContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.storage.AbstractStorageProvider;
import org.apache.james.mime4j.storage.Storage;
import org.apache.james.mime4j.storage.StorageOutputStream;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;
import org.apache.james.mime4j.util.ByteArrayBuffer;
import org.apache.james.mime4j.util.ByteSequence;
import org.jboss.resteasy.core.ProvidersContextRetainer;
import org.jboss.resteasy.plugins.providers.multipart.i18n.Messages;
import org.jboss.resteasy.resteasy_jaxrs.i18n.LogMessages;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.util.CaseInsensitiveMap;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.Providers;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class MultipartInputImpl implements MultipartInput, ProvidersContextRetainer {
    protected MediaType contentType;
    protected Providers workers;
    protected Message mimeMessage;
    protected List<InputPart> parts = new ArrayList<>();
    protected static final Annotation[] empty = {};
    protected MediaType defaultPartContentType = MultipartConstants.TEXT_PLAIN_WITH_CHARSET_US_ASCII_TYPE;
    protected String defaultPartCharset = null;
    protected Providers savedProviders;

    private static class BinaryOnlyMessageBuilder implements ContentHandler {
        private final Entity entity;
        private final BodyFactory bodyFactory;
        private final Stack<Object> stack;

        BinaryOnlyMessageBuilder(
                final Entity entity,
                final BodyFactory bodyFactory) {
            this.entity = entity;
            this.bodyFactory = bodyFactory;
            this.stack = new Stack<>();
        }

        private void expect(Class<?> c) {
            if (!c.isInstance(stack.peek())) {
                throw new IllegalStateException("Internal stack error: "
                        + "Expected '" + c.getName() + "' found '"
                        + stack.peek().getClass().getName() + "'");
            }
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#startMessage()
         */
        public void startMessage() throws MimeException {
            if (stack.isEmpty()) {
                stack.push(this.entity);
            } else {
                expect(Entity.class);
                Message m = new MessageImpl();
                ((Entity) stack.peek()).setBody(m);
                stack.push(m);
            }
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#endMessage()
         */
        public void endMessage() throws MimeException {
            expect(Message.class);
            stack.pop();
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#startHeader()
         */
        public void startHeader() throws MimeException {
            stack.push(new HeaderImpl());
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#field(RawField)
         */
        public void field(Field field) throws MimeException {
            expect(Header.class);
            ((Header) stack.peek()).addField(field);
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#endHeader()
         */
        public void endHeader() throws MimeException {
            expect(Header.class);
            Header h = (Header) stack.pop();
            expect(Entity.class);
            ((Entity) stack.peek()).setHeader(h);
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#startMultipart(org.apache.james.mime4j.stream.BodyDescriptor)
         */
        public void startMultipart(final BodyDescriptor bd) throws MimeException {
            expect(Entity.class);

            final Entity e = (Entity) stack.peek();
            final String subType = bd.getSubType();
            final Multipart multiPart = new MultipartImpl(subType);
            e.setBody(multiPart);
            stack.push(multiPart);
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#body(org.apache.james.mime4j.stream.BodyDescriptor, java.io.InputStream)
         */
        public void body(BodyDescriptor bd, final InputStream is) throws MimeException, IOException {
            expect(Entity.class);

            // NO NEED TO MANUALLY RUN DECODING.
            // The parser has a "setContentDecoding" method. We should
            // simply instantiate the MimeStreamParser with that method.

            // final String enc = bd.getTransferEncoding();

            final Body body;

        /*
        final InputStream decodedStream;
        if (MimeUtil.ENC_BASE64.equals(enc)) {
            decodedStream = new Base64InputStream(is);
        } else if (MimeUtil.ENC_QUOTED_PRINTABLE.equals(enc)) {
            decodedStream = new QuotedPrintableInputStream(is);
        } else {
            decodedStream = is;
        }
        */

            //Code change here to unilaterally use binaryBody
            //Code left commented out here to make diffs easy in the future when apache-mime4j updates.
            //if (bd.getMimeType().startsWith("text/")) {
            //    body = bodyFactory.textBody(is, bd.getCharset());
            //} else {
            body = bodyFactory.binaryBody(is);
            //}

            Entity entity = ((Entity) stack.peek());
            entity.setBody(body);
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#endMultipart()
         */
        public void endMultipart() throws MimeException {
            stack.pop();
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#startBodyPart()
         */
        public void startBodyPart() throws MimeException {
            expect(Multipart.class);

            BodyPart bodyPart = new BodyPart();
            ((Multipart) stack.peek()).addBodyPart(bodyPart);
            stack.push(bodyPart);
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#endBodyPart()
         */
        public void endBodyPart() throws MimeException {
            expect(BodyPart.class);
            stack.pop();
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#epilogue(java.io.InputStream)
         */
        public void epilogue(InputStream is) throws MimeException, IOException {
            expect(MultipartImpl.class);
            ByteSequence bytes = loadStream(is);
            ((MultipartImpl) stack.peek()).setEpilogueRaw(bytes);
        }

        /**
         * @see org.apache.james.mime4j.parser.ContentHandler#preamble(java.io.InputStream)
         */
        public void preamble(InputStream is) throws MimeException, IOException {
            expect(MultipartImpl.class);
            ByteSequence bytes = loadStream(is);
            ((MultipartImpl) stack.peek()).setPreambleRaw(bytes);
        }

        /**
         * Unsupported.
         *
         * @see org.apache.james.mime4j.parser.ContentHandler#raw(java.io.InputStream)
         */
        public void raw(InputStream is) throws MimeException, IOException {
            throw new UnsupportedOperationException("Not supported");
        }

        private static ByteSequence loadStream(InputStream in) throws IOException {
            ByteArrayBuffer bab = new ByteArrayBuffer(64);

            int b;
            while ((b = in.read()) != -1) {
                bab.append(b);
            }

            return bab;
        }
    }

    private static class BinaryMessage extends MessageImpl {
        private BinaryMessage(InputStream is) throws IOException {
            try {
                MimeConfig cfg = new MimeConfig();
                boolean strict = cfg.isStrictParsing();
                DecodeMonitor mon = strict ? DecodeMonitor.STRICT : DecodeMonitor.SILENT;
                DefaultBodyDescriptorBuilder bdb = new DefaultBodyDescriptorBuilder(null, strict ? DefaultFieldParser.getParser() : LenientFieldParser.getParser(), mon);
                BodyFactory bf = new BasicBodyFactory();
                MimeStreamParser parser = new MimeStreamParser(cfg, mon, bdb);
                // EntityBuilder expect the parser will send ParserFields for the well known fields
                // It will throw exceptions, otherwise.
                parser.setContentHandler(new BinaryOnlyMessageBuilder(this, bf));
                parser.setContentDecoding(false);
                parser.setRecurse();

                parser.parse(is);
            } catch (MimeException e) {
                throw new MimeIOException(e);
            }
        }
    }

    public MultipartInputImpl(MediaType contentType, Providers workers) {
        this.contentType = contentType;
        this.workers = workers;
        HttpRequest httpRequest = ResteasyProviderFactory
                .getContextData(HttpRequest.class);
        if (httpRequest != null) {
            String defaultContentType = (String) httpRequest
                    .getAttribute(InputPart.DEFAULT_CONTENT_TYPE_PROPERTY);
            if (defaultContentType != null)
                this.defaultPartContentType = MediaType
                        .valueOf(defaultContentType);
            this.defaultPartCharset = (String) httpRequest.getAttribute(InputPart.DEFAULT_CHARSET_PROPERTY);
            if (defaultPartCharset != null) {
                this.defaultPartContentType = getMediaTypeWithDefaultCharset(this.defaultPartContentType);
            }
        }
    }

    public MultipartInputImpl(MediaType contentType, Providers workers,
                              MediaType defaultPartContentType, String defaultPartCharset) {
        this.contentType = contentType;
        this.workers = workers;
        if (defaultPartContentType != null) this.defaultPartContentType = defaultPartContentType;
        this.defaultPartCharset = defaultPartCharset;
        if (defaultPartCharset != null) {
            this.defaultPartContentType = getMediaTypeWithDefaultCharset(this.defaultPartContentType);
        }
    }

    public MultipartInputImpl(Multipart multipart, Providers workers) throws IOException {
        for (Entity bodyPart : multipart.getBodyParts())
            parts.add(extractPart(bodyPart));
        this.workers = workers;
    }

    public void parse(InputStream is) throws IOException {
        mimeMessage = new BinaryMessage(addHeaderToHeadlessStream(is));
        extractParts();
    }

    protected InputStream addHeaderToHeadlessStream(InputStream is)
            throws UnsupportedEncodingException {
        return new SequenceInputStream(createHeaderInputStream(), is);
    }

    protected InputStream createHeaderInputStream()
            throws UnsupportedEncodingException {
        String header = HttpHeaders.CONTENT_TYPE + ": " + contentType
                + "\r\n\r\n";
        return new ByteArrayInputStream(header.getBytes(StandardCharsets.UTF_8));
    }

    public String getPreamble() {
        return ((Multipart) mimeMessage.getBody()).getPreamble();
    }

    public List<InputPart> getParts() {
        return parts;
    }

    protected void extractParts() throws IOException {
        Multipart multipart = (Multipart) mimeMessage.getBody();
        for (Entity entity : multipart.getBodyParts())
            if (entity instanceof BodyPart) {
                parts.add(extractPart(entity));
            }
    }

    protected InputPart extractPart(Entity bodyPart) throws IOException {
        return new PartImpl(bodyPart);
    }

    public class PartImpl implements InputPart {

        private Entity bodyPart;
        private MediaType contentType;
        private MultivaluedMap<String, String> headers = new CaseInsensitiveMap<>();
        private boolean contentTypeFromMessage;

        public PartImpl(Entity bodyPart) {
            this.bodyPart = bodyPart;
            for (Field field : bodyPart.getHeader()) {
                headers.add(field.getName(), field.getBody());
                if (field instanceof ContentTypeField) {
                    contentType = MediaType.valueOf(field.getBody());
                    contentTypeFromMessage = true;
                }
            }
            if (contentType == null)
                contentType = defaultPartContentType;
            if (getCharset(contentType) == null) {
                if (defaultPartCharset != null) {
                    contentType = getMediaTypeWithDefaultCharset(contentType);
                } else if (contentType.getType().equalsIgnoreCase("text")) {
                    contentType = getMediaTypeWithCharset(contentType, "us-ascii");
                }
            }
        }

        @Override
        public void setMediaType(MediaType mediaType) {
            contentType = mediaType;
            contentTypeFromMessage = false;
            headers.putSingle("Content-Type", mediaType.toString());
        }

        @SuppressWarnings("unchecked")
        public <T> T getBody(Class<T> type, Type genericType)
                throws IOException {
            if (MultipartInput.class.equals(type)) {
                if (bodyPart.getBody() instanceof Multipart) {
                    return (T) new MultipartInputImpl(Multipart.class.cast(bodyPart.getBody()), workers);
                }
            }
            try {
                if (savedProviders != null) {
                    ResteasyProviderFactory.pushContext(Providers.class, savedProviders);
                }
                MessageBodyReader<T> reader = workers.getMessageBodyReader(type, genericType, empty, contentType);
                if (reader == null) {
                    throw new RuntimeException(Messages.MESSAGES.unableToFindMessageBodyReader(contentType, type.getName()));
                }

                LogMessages.LOGGER.debugf("MessageBodyReader: %s", reader.getClass().getName());

                return reader.readFrom(type, genericType, empty, contentType, headers, getBody());
            } finally {
                if (savedProviders != null) {
                    ResteasyProviderFactory.popContextData(Providers.class);
                }
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T getBody(GenericType<T> type) throws IOException {
            return getBody((Class<T>) type.getRawType(), type.getType());
        }

        public InputStream getBody() throws IOException {
            Body body = bodyPart.getBody();
            InputStream result = null;
            if (body instanceof TextBody) {
                throw new UnsupportedOperationException();
            /*
            InputStreamReader reader = (InputStreamReader)((TextBody) body).getReader();
            StringBuilder inputBuilder = new StringBuilder();
            char[] buffer = new char[1024];
            while (true) {
               int readCount = reader.read(buffer);
               if (readCount < 0) {
                  break;
               }
               inputBuilder.append(buffer, 0, readCount);
            }
            String str = inputBuilder.toString();
            return new ByteArrayInputStream(str.getBytes(reader.getEncoding()));
            */
            } else if (body instanceof BinaryBody) {
                return ((BinaryBody) body).getInputStream();
            }
            return result;
        }

        public String getBodyAsString() throws IOException {
            return getBody(String.class, null);
        }

        public MultivaluedMap<String, String> getHeaders() {
            return headers;
        }

        public MediaType getMediaType() {
            return contentType;
        }

        public boolean isContentTypeFromMessage() {
            return contentTypeFromMessage;
        }
    }

    public static void main(String[] args) throws Exception {
        String input = "URLSTR: file:/Users/billburke/jboss/resteasy-jaxrs/resteasy-jaxrs/src/test/test-data/data.txt\r\n"
                + "--B98hgCmKsQ-B5AUFnm2FnDRCgHPDE3\r\n"
                + "Content-Disposition: form-data; name=\"part1\"\r\n"
                + "Content-Type: text/plain; charset=US-ASCII\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + "This is Value 1\r\n"
                + "--B98hgCmKsQ-B5AUFnm2FnDRCgHPDE3\r\n"
                + "Content-Disposition: form-data; name=\"part2\"\r\n"
                + "Content-Type: text/plain; charset=US-ASCII\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + "This is Value 2\r\n"
                + "--B98hgCmKsQ-B5AUFnm2FnDRCgHPDE3\r\n"
                + "Content-Disposition: form-data; name=\"data.txt\"; filename=\"data.txt\"\r\n"
                + "Content-Type: application/octet-stream; charset=ISO-8859-1\r\n"
                + "Content-Transfer-Encoding: binary\r\n"
                + "\r\n"
                + "hello world\r\n" + "--B98hgCmKsQ-B5AUFnm2FnDRCgHPDE3--";
        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes());
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.put("boundary", "B98hgCmKsQ-B5AUFnm2FnDRCgHPDE3");
        MediaType contentType = new MediaType("multipart", "form-data",
                parameters);
        MultipartInputImpl multipart = new MultipartInputImpl(contentType, null);
        multipart.parse(bais);

        System.out.println(multipart.getPreamble());
        System.out.println("**********");
        for (InputPart part : multipart.getParts()) {
            System.out.println("--");
            System.out.println("\"" + part.getBodyAsString() + "\"");
        }
        System.out.println("done");

    }

    @Override
    public void close() {
        if (mimeMessage != null) {
            try {
                mimeMessage.dispose();
            } catch (Exception e) {

            }
        }
    }

    protected void finalize() throws Throwable {
        close();
    }

    protected String getCharset(MediaType mediaType) {
        for (Iterator<String> it = mediaType.getParameters().keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            if ("charset".equalsIgnoreCase(key)) {
                return mediaType.getParameters().get(key);
            }
        }
        return null;
    }

    private MediaType getMediaTypeWithDefaultCharset(MediaType mediaType) {
        String charset = defaultPartCharset;
        return getMediaTypeWithCharset(mediaType, charset);
    }

    private MediaType getMediaTypeWithCharset(MediaType mediaType, String charset) {
        Map<String, String> params = mediaType.getParameters();
        Map<String, String> newParams = new LinkedHashMap<String, String>();
        newParams.put("charset", charset);
        for (Iterator<String> it = params.keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            if (!"charset".equalsIgnoreCase(key)) {
                newParams.put(key, params.get(key));
            }
        }
        return new MediaType(mediaType.getType(), mediaType.getSubtype(), newParams);
    }

    @Override
    public void setProviders(Providers providers) {
        savedProviders = providers;
    }

}
