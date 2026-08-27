// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  mainSidebar: [
    'intro',
    {
      type: 'category',
      label: 'Agents',
      link: {type: 'doc', id: 'agents/overview'},
      items: [
        'agents/getting-started',
        'agents/how-it-compares',
        {
          type: 'category',
          label: 'Admin UI',
          link: {type: 'doc', id: 'agents/admin-ui/index'},
          items: [
            'agents/admin-ui/agents',
            'agents/admin-ui/llm-configs',
            'agents/admin-ui/tools',
            'agents/admin-ui/vector-stores',
            'agents/admin-ui/migrations',
          ],
        },
        'agents/cli',
        'agents/llm-gateway',
        {
          type: 'category',
          label: 'Concepts',
          items: [
            {
              type: 'category',
              label: 'Sub-agents',
              link: {type: 'doc', id: 'agents/sub-agents'},
              items: [
                'agents/research-lead-flow',
              ],
            },
            'agents/memory',
            'agents/prompt-renderer',
            'agents/workspace',
            'agents/vector-store',
            'agents/persistence',
            'agents/creating-a-tool',
          ],
        },
        {
          type: 'category',
          label: 'Configuration & reference',
          items: [
            'agents/environment-variables',
            'agents/initial-data',
            'agents/agent-json',
            'agents/llm-config-json',
            'agents/llm-configs-reference',
            'agents/bundled-agents',
            'agents/built-in-tools',
          ],
        },
        'agents/modules',
      ],
    },
    {
      type: 'link',
      label: 'Semantic UI ↗',
      href: 'https://mindconnect-ai.github.io/mc-semantic-ui/',
    },
    {
      type: 'category',
      label: 'Workflow',
      link: {type: 'doc', id: 'workflow/overview'},
      items: [
        'workflow/how-it-works',
        'workflow/getting-started',
        'workflow/step-reference',
        'workflow/puml-reference',
        'workflow/miniscript',
        'workflow/variable-scope',
        'workflow/custom-steps',
      ],
    },
    {
      type: 'category',
      label: 'Task queue',
      link: {type: 'doc', id: 'taskqueue/overview'},
      items: [
        'taskqueue/getting-started',
        'taskqueue/suspend-and-wake',
        'taskqueue/channels',
        'taskqueue/persistence-and-clustering',
        'taskqueue/scheduling',
      ],
    },
  ],
};

export default sidebars;
