package cc.thonly.wikikernel.user;

import cc.thonly.wikikernel.common.data.WikiUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class UserSessionTest {

    @Test
    void loginRotatesExistingSessionIdAndKeepsAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("csrf", "token");
        request.setSession(session);
        String previousId = session.getId();

        WikiUser user = new WikiUser();
        user.setId(7L);
        user.setUsername("session-user");
        UserSession.login(request, user.toTransferCopy(), true);

        assertEquals("token", request.getSession(false).getAttribute("csrf"));
        assertNotEquals(previousId, request.getSession(false).getId());
        assertEquals("session-user", UserSession.current(request).getUsername());
    }
}
