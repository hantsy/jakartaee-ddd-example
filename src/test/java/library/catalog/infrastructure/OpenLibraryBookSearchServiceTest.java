package library.catalog.infrastructure;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import jakarta.ws.rs.ProcessingException;
import library.catalog.domain.BookInformation;
import library.catalog.domain.BookNotFoundException;
import library.catalog.domain.BookSearchException;
import library.catalog.domain.BookSearchService;
import library.catalog.domain.Isbn;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpenLibraryBookSearchService}, the infrastructure adapter
 * behind the {@link BookSearchService} domain port.
 * <p>
 * The Open Library endpoint is mocked with WireMock on a fixed local port: the
 * adapter is pointed at {@code http://localhost:8080} via its configurable base
 * URL, so the tests exercise the real JAX-RS client stack against stubbed success
 * and failure responses, with no external network access.
 */
@WireMockTest(httpPort = 8080)
class OpenLibraryBookSearchServiceTest {

    /** Base URL of the WireMock server, mirroring the real Open Library API. */
    private static final String BASE_URL = "http://localhost:8080/";

    /** A valid ISBN-13 (checksum correct) that is not allocated to any book. */
    private static final String UNKNOWN_ISBN = "9780000000002";

    private static final String KNOWN_ISBN = "9780134685991";

    /** The adapter under test, pointed at the WireMock server base URL. */
    private final BookSearchService service = new OpenLibraryBookSearchService(BASE_URL);

    @Test
    void searchWithKnownIsbnShouldReturnBookInformation() {
        stubFor(get(urlEqualTo("/isbn/" + KNOWN_ISBN + ".json"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "/books/OL31838212M.json")));

        stubFor(get(urlEqualTo("/books/OL31838212M.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "title": "Effective Java",
                                  "publishers": ["Addison-Wesley"],
                                  "isbn_13": ["9780134685991"],
                                  "revisions": 1
                                }
                                """)));

        BookInformation result = service.search(new Isbn(KNOWN_ISBN));

        assertThat(result.title()).isEqualTo("Effective Java");

        verify(getRequestedFor(urlEqualTo("/isbn/" + KNOWN_ISBN + ".json"))
                .withHeader("Accept", equalTo("application/json")));
        verify(getRequestedFor(urlEqualTo("/books/OL31838212M.json"))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void searchWithUnknownIsbnShouldThrowBookNotFoundExceptionWhenUpstreamReturns404() {
        stubFor(get(urlEqualTo("/isbn/" + UNKNOWN_ISBN + ".json"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> service.search(new Isbn(UNKNOWN_ISBN)))
                .isInstanceOf(BookNotFoundException.class)
                .hasMessageContaining(UNKNOWN_ISBN);

        verify(getRequestedFor(urlEqualTo("/isbn/" + UNKNOWN_ISBN + ".json")));
    }

    @Test
    void searchShouldThrowBookSearchExceptionWhenUpstreamReturnsErrorStatus() {
        stubFor(get(urlEqualTo("/isbn/" + UNKNOWN_ISBN + ".json"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.search(new Isbn(UNKNOWN_ISBN)))
                .isInstanceOf(BookSearchException.class)
                .hasMessageContaining("500");
    }

    @Test
    void searchShouldThrowBookSearchExceptionWhenNetworkFails() {
        stubFor(get(urlEqualTo("/isbn/" + UNKNOWN_ISBN + ".json"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> service.search(new Isbn(UNKNOWN_ISBN)))
                .isInstanceOf(BookSearchException.class)
                .hasMessageContaining(UNKNOWN_ISBN)
                .hasCauseInstanceOf(ProcessingException.class);
    }
}
