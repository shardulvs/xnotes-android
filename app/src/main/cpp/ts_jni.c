// The whole JNI surface for code highlighting: stateless calls that parse a
// code block, run a highlight query (.scm) and return flat capture spans, so
// no native handles ever cross the boundary and there are no lifetimes to leak.
#include <jni.h>
#include <regex.h>
#include <stdbool.h>

#include <stdlib.h>
#include <string.h>
#include "tree_sitter/api.h"

const TSLanguage *tree_sitter_asm(void);
const TSLanguage *tree_sitter_bash(void);
const TSLanguage *tree_sitter_c(void);
const TSLanguage *tree_sitter_cpp(void);
const TSLanguage *tree_sitter_css(void);
const TSLanguage *tree_sitter_diff(void);
const TSLanguage *tree_sitter_dockerfile(void);
const TSLanguage *tree_sitter_go(void);
const TSLanguage *tree_sitter_html(void);
const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_javascript(void);
const TSLanguage *tree_sitter_json(void);
const TSLanguage *tree_sitter_kotlin(void);
const TSLanguage *tree_sitter_lua(void);
const TSLanguage *tree_sitter_php_only(void);
const TSLanguage *tree_sitter_python(void);
const TSLanguage *tree_sitter_rust(void);
const TSLanguage *tree_sitter_toml(void);
const TSLanguage *tree_sitter_typescript(void);
const TSLanguage *tree_sitter_xml(void);
const TSLanguage *tree_sitter_yaml(void);

typedef struct {
    const char *name;
    const TSLanguage *(*fn)(void);
} LangEntry;

static const LangEntry LANGS[] = {
    {"asm", tree_sitter_asm},
    {"bash", tree_sitter_bash},
    {"c", tree_sitter_c},
    {"cpp", tree_sitter_cpp},
    {"css", tree_sitter_css},
    {"diff", tree_sitter_diff},
    {"dockerfile", tree_sitter_dockerfile},
    {"go", tree_sitter_go},
    {"html", tree_sitter_html},
    {"java", tree_sitter_java},
    {"javascript", tree_sitter_javascript},
    {"json", tree_sitter_json},
    {"kotlin", tree_sitter_kotlin},
    {"lua", tree_sitter_lua},
    {"php", tree_sitter_php_only},
    {"python", tree_sitter_python},
    {"rust", tree_sitter_rust},
    {"toml", tree_sitter_toml},
    {"typescript", tree_sitter_typescript},
    {"xml", tree_sitter_xml},
    {"yaml", tree_sitter_yaml},
};

// --- query predicate evaluation ---
// ts_query returns #eq?/#match?-style predicates for the CALLER to check; without
// this, constrained captures over-apply (every identifier lights up as a constant).
// Supported: eq?, not-eq?, any-of?, not-any-of?, match?, not-match? (POSIX ERE).
// Unknown predicates and directives (#set! etc.) pass, so exotic queries degrade
// to over-highlighting instead of dropping out.

static bool match_capture_text(const TSQueryMatch *m, uint32_t value_id,
                               const char *text, const char **out, uint32_t *out_len) {
    for (uint16_t i = 0; i < m->capture_count; i++) {
        if (m->captures[i].index == value_id) {
            TSNode node = m->captures[i].node;
            uint32_t start = ts_node_start_byte(node);
            *out = text + start;
            *out_len = ts_node_end_byte(node) - start;
            return true;
        }
    }
    return false;
}

static bool regex_matches(const char *pattern, uint32_t plen, const char *s, uint32_t slen) {
    char *pat = malloc(plen + 1);
    char *str = malloc(slen + 1);
    bool result = false;
    if (pat && str) {
        memcpy(pat, pattern, plen);
        pat[plen] = 0;
        memcpy(str, s, slen);
        str[slen] = 0;
        regex_t re;
        if (regcomp(&re, pat, REG_EXTENDED | REG_NOSUB) == 0) {
            result = regexec(&re, str, 0, NULL, 0) == 0;
            regfree(&re);
        }
    }
    free(pat);
    free(str);
    return result;
}

static bool pattern_predicates_pass(const TSQuery *query, const TSQueryMatch *m, const char *text) {
    uint32_t step_count;
    const TSQueryPredicateStep *steps =
        ts_query_predicates_for_pattern(query, m->pattern_index, &step_count);
    uint32_t i = 0;
    while (i < step_count) {
        // One predicate runs from here to the next Done step.
        uint32_t end = i;
        while (end < step_count && steps[end].type != TSQueryPredicateStepTypeDone) end++;
        if (steps[i].type == TSQueryPredicateStepTypeString && end > i) {
            uint32_t name_len;
            const char *name = ts_query_string_value_for_id(query, steps[i].value_id, &name_len);
            bool negate = strncmp(name, "not-", 4) == 0;
            const char *op = negate ? name + 4 : name;
            uint32_t op_len = negate ? name_len - 4 : name_len;
            bool known = false;
            bool pass = true;
            const char *cap_text = NULL;
            uint32_t cap_len = 0;
            bool have_cap = end > i + 1 &&
                steps[i + 1].type == TSQueryPredicateStepTypeCapture &&
                match_capture_text(m, steps[i + 1].value_id, text, &cap_text, &cap_len);

            if (op_len == 3 && strncmp(op, "eq?", 3) == 0 && have_cap && end > i + 2) {
                known = true;
                if (steps[i + 2].type == TSQueryPredicateStepTypeString) {
                    uint32_t vlen;
                    const char *v = ts_query_string_value_for_id(query, steps[i + 2].value_id, &vlen);
                    pass = cap_len == vlen && strncmp(cap_text, v, vlen) == 0;
                } else {
                    const char *other = NULL;
                    uint32_t other_len = 0;
                    pass = match_capture_text(m, steps[i + 2].value_id, text, &other, &other_len) &&
                        cap_len == other_len && strncmp(cap_text, other, cap_len) == 0;
                }
            } else if (op_len == 6 && strncmp(op, "match?", 6) == 0 && have_cap && end > i + 2 &&
                       steps[i + 2].type == TSQueryPredicateStepTypeString) {
                known = true;
                uint32_t plen;
                const char *pat = ts_query_string_value_for_id(query, steps[i + 2].value_id, &plen);
                pass = regex_matches(pat, plen, cap_text, cap_len);
            } else if (op_len == 7 && strncmp(op, "any-of?", 7) == 0 && have_cap) {
                known = true;
                pass = false;
                for (uint32_t a = i + 2; a < end; a++) {
                    if (steps[a].type != TSQueryPredicateStepTypeString) continue;
                    uint32_t vlen;
                    const char *v = ts_query_string_value_for_id(query, steps[a].value_id, &vlen);
                    if (cap_len == vlen && strncmp(cap_text, v, vlen) == 0) {
                        pass = true;
                        break;
                    }
                }
            }
            if (known) {
                if (negate) pass = !pass;
                if (!pass) return false;
            }
        }
        i = end + 1;
    }
    return true;
}

static const TSLanguage *lang_for(JNIEnv *env, jstring jlang) {
    const char *name = (*env)->GetStringUTFChars(env, jlang, NULL);
    if (!name) return NULL;
    const TSLanguage *result = NULL;
    for (size_t i = 0; i < sizeof(LANGS) / sizeof(LANGS[0]); i++) {
        if (strcmp(LANGS[i].name, name) == 0) {
            result = LANGS[i].fn();
            break;
        }
    }
    (*env)->ReleaseStringUTFChars(env, jlang, name);
    return result;
}

static TSQuery *make_query(const TSLanguage *lang, JNIEnv *env, jbyteArray jscm,
                           uint32_t *err_offset, TSQueryError *err_type) {
    jsize len = (*env)->GetArrayLength(env, jscm);
    jbyte *scm = (*env)->GetByteArrayElements(env, jscm, NULL);
    if (!scm) return NULL;
    TSQuery *query = ts_query_new(lang, (const char *)scm, (uint32_t)len, err_offset, err_type);
    (*env)->ReleaseByteArrayElements(env, jscm, scm, JNI_ABORT);
    return query;
}

// -> flat [startByte, endByte, captureIndex] triples, or null on unknown
//    language / malformed query.
JNIEXPORT jintArray JNICALL
Java_com_xnotes_platform_TreeSitterNative_nativeHighlight(
    JNIEnv *env, jclass cls, jstring jlang, jbyteArray jtext, jbyteArray jscm) {
    const TSLanguage *lang = lang_for(env, jlang);
    if (!lang) return NULL;

    uint32_t err_offset;
    TSQueryError err_type;
    TSQuery *query = make_query(lang, env, jscm, &err_offset, &err_type);
    if (!query) return NULL;

    jsize text_len = (*env)->GetArrayLength(env, jtext);
    jbyte *text = (*env)->GetByteArrayElements(env, jtext, NULL);
    if (!text) {
        ts_query_delete(query);
        return NULL;
    }

    TSParser *parser = ts_parser_new();
    ts_parser_set_language(parser, lang);
    TSTree *tree = ts_parser_parse_string(parser, NULL, (const char *)text, (uint32_t)text_len);

    jintArray out = NULL;
    if (tree) {
        TSQueryCursor *cursor = ts_query_cursor_new();
        ts_query_cursor_exec(cursor, query, ts_tree_root_node(tree));
        uint32_t cap = 256, n = 0;
        jint *buf = malloc(cap * 3 * sizeof(jint));
        TSQueryMatch match;
        while (buf && ts_query_cursor_next_match(cursor, &match)) {
            if (!pattern_predicates_pass(query, &match, (const char *)text)) continue;
            for (uint16_t i = 0; i < match.capture_count; i++) {
                if (n == cap) {
                    cap *= 2;
                    jint *grown = realloc(buf, cap * 3 * sizeof(jint));
                    if (!grown) { free(buf); buf = NULL; break; }
                    buf = grown;
                }
                TSNode node = match.captures[i].node;
                buf[n * 3 + 0] = (jint)ts_node_start_byte(node);
                buf[n * 3 + 1] = (jint)ts_node_end_byte(node);
                buf[n * 3 + 2] = (jint)match.captures[i].index;
                n++;
            }
        }
        if (buf) {
            out = (*env)->NewIntArray(env, (jsize)(n * 3));
            if (out) (*env)->SetIntArrayRegion(env, out, 0, (jsize)(n * 3), buf);
            free(buf);
        }
        ts_query_cursor_delete(cursor);
        ts_tree_delete(tree);
    }
    ts_parser_delete(parser);
    ts_query_delete(query);
    (*env)->ReleaseByteArrayElements(env, jtext, text, JNI_ABORT);
    return out;
}

// -> the query's capture names, indexed by the captureIndex nativeHighlight emits.
JNIEXPORT jobjectArray JNICALL
Java_com_xnotes_platform_TreeSitterNative_nativeCaptureNames(
    JNIEnv *env, jclass cls, jstring jlang, jbyteArray jscm) {
    const TSLanguage *lang = lang_for(env, jlang);
    if (!lang) return NULL;
    uint32_t err_offset;
    TSQueryError err_type;
    TSQuery *query = make_query(lang, env, jscm, &err_offset, &err_type);
    if (!query) return NULL;

    uint32_t count = ts_query_capture_count(query);
    jclass str = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, (jsize)count, str, NULL);
    for (uint32_t i = 0; out && i < count; i++) {
        uint32_t len;
        const char *name = ts_query_capture_name_for_id(query, i, &len);
        char *copy = malloc(len + 1);
        if (copy) {
            memcpy(copy, name, len);
            copy[len] = 0;
            jstring jname = (*env)->NewStringUTF(env, copy);
            (*env)->SetObjectArrayElement(env, out, (jsize)i, jname);
            (*env)->DeleteLocalRef(env, jname);
            free(copy);
        }
    }
    ts_query_delete(query);
    return out;
}

