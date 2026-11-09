package com.aurora.appserver.support;

import java.io.IOException;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * For application contexts started manually inside tests (e.g. restart simulation): keeps
 * per-test {@code @TestConfiguration} classes out of component scanning, as @SpringBootTest does.
 */
public class TestConfigurationExcludeFilter extends TypeExcludeFilter {

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
            throws IOException {
        return metadataReader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass() == getClass();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
