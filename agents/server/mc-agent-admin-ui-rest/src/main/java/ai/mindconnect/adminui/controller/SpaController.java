package ai.mindconnect.adminui.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaController {

    // Matches all /admin/* paths that do NOT contain /api/ — REST controllers
    // registered under /admin/api/** take precedence and are never reached here.
    /** The root lands in the chat — the admin sections are one click away. */
    @RequestMapping("/")
    public String root() {
        return "redirect:/chat";
    }

    @RequestMapping({
        "/chat",
        "/chat/**",
        "/admin",
        "/admin/agents",
        "/admin/agents/**",
        "/admin/tools",
        "/admin/tools/**",
        "/admin/sessions",
        "/admin/sessions/**",
        "/admin/llm-configs",
        "/admin/llm-configs/**",
        "/admin/migrations",
        "/admin/migrations/**"
    })
    public String spa() {
        return "forward:/index.html";
    }

    @RequestMapping("/auth/callback")
    public String authCallback() {
        return "forward:/index.html";
    }

    /**
     * Serves the static login landing page. Spring won't auto-map
     * extensionless paths to {@code .html} files in {@code static/}, so
     * we forward {@code /login} to {@code /login.html} explicitly.
     * Keeps the user-facing URL clean ({@code /login}, no {@code .html}
     * suffix) without committing to a Thymeleaf / view-resolver setup.
     */
    @RequestMapping("/login")
    public String loginPage() {
        return "forward:/login.html";
    }
}
