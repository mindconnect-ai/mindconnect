// @ts-check
import {themes as prismThemes} from 'prism-react-renderer';

// The site is served by GitHub Pages from THIS repo. Owner and repo name are
// derived from the CI environment (GITHUB_REPOSITORY = "owner/repo"), so a
// fork or rename keeps working; local dev falls back to the defaults below.
const [owner, repo] = (process.env.GITHUB_REPOSITORY ?? 'mindconnect-ai/mindconnect').split('/');

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Mindconnect',
  tagline: 'LLM-powered agents, a workflow engine, and a task queue',
  favicon: 'img/favicon.svg',

  url: `https://${owner}.github.io`,
  baseUrl: `/${repo}/`,

  organizationName: owner,
  projectName: repo,

  onBrokenLinks: 'warn',

  i18n: {defaultLocale: 'en', locales: ['en']},

  markdown: {
    mermaid: true,
    hooks: {onBrokenMarkdownLinks: 'warn'},
  },
  themes: ['@docusaurus/theme-mermaid'],

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
          routeBasePath: '/',
          editUrl: `https://github.com/${owner}/${repo}/edit/main/website/`,
        },
        blog: false,
        theme: {customCss: './src/css/custom.css'},
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'img/agents/research-lead-flow.svg',
      colorMode: {defaultMode: 'light', respectPrefersColorScheme: true},
      navbar: {
        title: 'Mindconnect',
        logo: {
          alt: 'Mindconnect logo',
          src: 'img/logo.svg',
          srcDark: 'img/logo-dark.svg',
        },
        items: [
          {type: 'docSidebar', sidebarId: 'mainSidebar', position: 'left', label: 'Docs'},
          {
            // The UI framework lives in its own repo with its own docs site.
            href: 'https://mindconnect-ai.github.io/mc-semantic-ui/',
            label: 'Semantic UI',
            position: 'left',
          },
          {
            href: 'https://github.com/mindconnect-ai/mindconnect',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              {label: 'Agents', to: '/agents/overview'},
              {label: 'Semantic UI', href: 'https://mindconnect-ai.github.io/mc-semantic-ui/'},
            ],
          },
          {
            title: 'Project',
            items: [
              {label: 'GitHub', href: 'https://github.com/mindconnect-ai/mindconnect'},
              {label: 'Discussions', href: 'https://github.com/mindconnect-ai/mindconnect/discussions'},
              {label: 'Support (Ko-fi)', href: 'https://ko-fi.com/beisdog'},
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} David Beisert. Apache-2.0.`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['java', 'bash', 'json', 'yaml'],
      },
    }),
};

export default config;
