package library.catalog.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import library.catalog.domain.BookInformation;
import library.catalog.domain.BookNotFoundException;
import library.catalog.domain.BookSearchException;
import library.catalog.domain.BookSearchService;
import library.catalog.domain.Isbn;

import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class OpenLibraryBookSearchService implements BookSearchService {
    private static final Logger LOGGER = Logger.getLogger(OpenLibraryBookSearchService.class.getName());

    /** The default Open Library API base URL. */
    public static final String DEFAULT_BASE_URL = "https://openlibrary.org/";

    private final Client client;
    private final String baseUrl;

    public OpenLibraryBookSearchService() {
        this(DEFAULT_BASE_URL);
    }

    /**
     * Points the adapter at a custom base URL instead of the real Open Library
     * API. Intended for tests that mock the endpoint (e.g. with WireMock), where
     * the base URL is the WireMock server address.
     */
    OpenLibraryBookSearchService(String baseUrl) {
        this.client = ClientBuilder.newClient();
        this.baseUrl = baseUrl;
    }

    public BookInformation search(Isbn isbn) {
        var targetUri = UriBuilder
                .fromUri(baseUrl + "isbn/{isbn}.json")
                .build(isbn.value());
        var target = this.client.target(targetUri);
        try (var response = target.request().accept(MediaType.APPLICATION_JSON_TYPE).get()) {
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                throw new BookNotFoundException(isbn);
            }
            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                LOGGER.log(Level.WARNING, "OpenLibrary returned unexpected status {0} for isbn {1}",
                        new Object[]{response.getStatus(), isbn.value()});
                throw new BookSearchException(
                        "failed to search book, upstream returned status " + response.getStatus());
            }
            var result = response.readEntity(OpenLibraryIsbnSearchResult.class);
            LOGGER.log(Level.FINEST, "Book search result: {0}", result);
            return new BookInformation(result.title());
        } catch (ProcessingException e) {
            LOGGER.log(Level.SEVERE, "network error searching isbn {0}: {1}",
                    new Object[]{isbn.value(), e.getMessage()});
            throw new BookSearchException("failed to search book for isbn: " + isbn.value(), e);
        }
    }
}

