package library.it;

import jakarta.inject.Inject;
import library.catalog.domain.BookInformation;
import library.catalog.domain.BookNotFoundException;
import library.catalog.domain.BookSearchException;
import library.catalog.domain.BookSearchService;
import library.catalog.domain.Isbn;
import library.catalog.infrastructure.OpenLibraryBookSearchService;
import library.catalog.infrastructure.OpenLibraryIsbnSearchResult;
import library.common.DomainException;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.container.annotation.ArquillianTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the {@link BookSearchService} domain port, executed inside
 * the container (GlassFish/WildFly) via Arquillian.
 * <p>
 * The deployment is a minimal jar containing only the search service and the
 * classes it needs: {@code Isbn} validates against commons-validator at runtime,
 * and the test assertions use AssertJ. No persistence unit or web archive is
 * required.
 * <p>
 * Requires outbound network access to {@code https://openlibrary.org/}; run with
 * {@code mvn clean verify -Parq-glassfish-managed} (or {@code -Parq-wildfly-managed}).
 * <p>
 * Currently disabled: a default GlassFish domain sets {@code javax.net.ssl.trustStore} JVM-wide to its
 * own two-entry {@code config/cacerts.p12}, so this adapter cannot validate Open Library's public
 * certificate in-container. The same test passes on WildFly. See the {@code @Disabled} reason below.
 */
@ArquillianTest
@Disabled("GlassFish sets -Djavax.net.ssl.trustStore JVM-wide to the domain's config/cacerts.p12, which holds "
        + "only the s1as/glassfish-instance self-signed certs and no CA roots, so the in-container JAX-RS client "
        + "fails PKIX validation against openlibrary.org. Passes on WildFly. Upstream: "
        + "https://github.com/eclipse-ee4j/glassfish/issues/26258 - re-enable once GlassFish trusts the JDK's "
        + "CA roots in a standalone domain.")
public class BookSearchServiceIT {

    @Deployment
    public static JavaArchive createDeployment() {
        JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "BookSearchServiceIT.jar");

        // --- Domain layer (library.catalog.domain): the port contract under test ---
        // BookSearchService is the port injected into the test; the value objects
        // and exceptions below are part of its contract.
        jar.addClasses(
                BookSearchService.class,        // domain port (the injected seam)
                BookInformation.class,           // value object: search result
                Isbn.class,                      // value object: search input, validated by commons-validator
                BookNotFoundException.class,     // domain exception: upstream returned 404
                BookSearchException.class);      // domain exception: upstream/network failure

        // --- Shared kernel (library.common): base class of the domain exceptions ---
        jar.addClasses(
                DomainException.class);          // abstract base of all domain exceptions

        // --- Infrastructure layer (library.catalog.infrastructure): the adapter under test ---
        jar.addClasses(
                OpenLibraryBookSearchService.class, // BookSearchService implementation (JAX-RS client -> Open Library)
                OpenLibraryIsbnSearchResult.class); // DTO unmarshalling the Open Library /isbn/{isbn}.json payload

        // --- External runtime dependencies, pulled from the test classpath ---
        // org.apache.commons: Isbn validates against commons-validator (ISBNValidator) in-container
        // org.assertj: AssertJ assertions run inside the container
        jar.addPackages(true, "org.apache.commons", "org.assertj");

        // Empty beans.xml marks the jar as a CDI bean archive, so the
        // @ApplicationScoped OpenLibraryBookSearchService is discovered and
        // @Inject BookSearchService resolves.
        jar.addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");

        return jar;
    }

    @Inject
    private BookSearchService bookSearchService;

    @Test
    public void searchEffectiveJavaIsbnReturnsBookInformation() {
        BookInformation result = bookSearchService.search(new Isbn("9780134685991"));

        assertThat(result.title()).isEqualTo("Effective Java");
    }

    @Test
    public void searchUnknownIsbnThrowsBookNotFoundException() {
        // 978-0-99999999-8 is a checksum-valid ISBN in an unallocated range:
        // Open Library returns 404, which the adapter maps to BookNotFoundException.
        assertThatThrownBy(() -> bookSearchService.search(new Isbn("9780999999998")))
                .isInstanceOf(BookNotFoundException.class)
                .hasMessageContaining("9780999999998");
    }
}
