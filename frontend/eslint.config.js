const js = require('@eslint/js');
const react = require('eslint-plugin-react');
const reactHooks = require('eslint-plugin-react-hooks');
const globals = require('globals');

module.exports = [
    { ignores: ['**/build/**', '**/node_modules/**', '**/public/**', '**/*.min.js'] },
    js.configs.recommended,
    {
        files: ['**/*.{js,jsx,mjs,cjs}'],
        plugins: { react, 'react-hooks': reactHooks },
        languageOptions: {
            ecmaVersion: 'latest',
            sourceType: 'module',
            parserOptions: { ecmaFeatures: { jsx: true } },
            globals: { ...globals.browser, ...globals.node, ...globals.es2021, process: 'readonly' },
        },
        settings: { react: { version: 'detect' } },
        rules: {
            ...react.configs.recommended.rules,
            ...reactHooks.configs.recommended.rules,
            'react/react-in-jsx-scope': 'off',
            'react/prop-types': 'off',
            'react/display-name': 'off',
            'react/no-unescaped-entities': 'off',
            'react/no-unknown-property': 'warn',
            'react-hooks/exhaustive-deps': 'off',
            'react-hooks/rules-of-hooks': 'off',
            'no-unused-vars': 'warn',
            'no-empty': 'warn',
            'no-undef': 'warn',
            'no-dupe-keys': 'warn',
            'no-misleading-character-class': 'warn',
            'no-useless-escape': 'off',
            'no-control-regex': 'off',
            'no-prototype-builtins': 'off',
            'no-fallthrough': 'warn',
            'no-cond-assign': 'warn',
            'no-case-declarations': 'warn',
        },
    },
];
