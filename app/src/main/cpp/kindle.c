#ifdef __ANDROID__
#include <jni.h>
#endif
#include <stdio.h>
#include <stdint.h>
#include "mobi.h"

enum { OK, INVALID, ENCRYPTED, TOO_LARGE, IO_FAILURE };
static const uint64_t MAX_OUTPUT = 160u * 1024u * 1024u;

static int write_parts(const char *directory, const char *prefix, const MOBIPart *part,
                       uint64_t *remaining, int *files) {
    for (; part; part = part->next) {
        if (!part->size) continue;
        if (part->size > *remaining || ++*files > 10000) return TOO_LARGE;
        MOBIFileMeta meta = mobi_get_filemeta_by_type(part->type);
        char path[4096];
        int length = snprintf(path, sizeof(path), "%s/%s%05zu.%s",
                              directory, prefix, part->uid, meta.extension);
        if (length < 0 || (size_t) length >= sizeof(path)) return IO_FAILURE;
        FILE *file = fopen(path, "wb");
        if (!file) return IO_FAILURE;
        size_t written = fwrite(part->data, 1, part->size, file);
        int closed = fclose(file);
        if (written != part->size || closed != 0) return IO_FAILURE;
        *remaining -= part->size;
    }
    return OK;
}

int unpack_kindle(const char *input, const char *directory) {
    int result = IO_FAILURE;
    MOBIData *book = NULL;
    MOBIRawml *raw = NULL;
    if (!input || !directory) goto cleanup;
    book = mobi_init();
    if (!book) goto cleanup;
    MOBI_RET loaded = mobi_load_filename(book, input);
    if (loaded == MOBI_FILE_ENCRYPTED || loaded == MOBI_DRM_UNSUPPORTED ||
        (loaded == MOBI_SUCCESS && mobi_is_encrypted(book))) {
        result = ENCRYPTED;
        goto cleanup;
    }
    result = INVALID;
    if (loaded != MOBI_SUCCESS) goto cleanup;
    raw = mobi_init_rawml(book);
    if (!raw || mobi_parse_rawml_opt(raw, book, false, false, true) != MOBI_SUCCESS || !raw->markup)
        goto cleanup;

    uint64_t remaining = MAX_OUTPUT;
    int files = 0;
    result = write_parts(directory, "part", raw->markup, &remaining, &files);
    if (result == OK && raw->flow)
        result = write_parts(directory, "flow", raw->flow->next, &remaining, &files);
    if (result == OK)
        result = write_parts(directory, "resource", raw->resources, &remaining, &files);

cleanup:
    if (raw) mobi_free_rawml(raw);
    if (book) mobi_free(book);
    return result;
}

#ifdef __ANDROID__
JNIEXPORT jint JNICALL
Java_com_paperlight_reader_data_KindleImport_unpack(JNIEnv *env, jobject self,
                                                   jstring source, jstring target) {
    (void) self;
    const char *input = (*env)->GetStringUTFChars(env, source, NULL);
    const char *directory = input ? (*env)->GetStringUTFChars(env, target, NULL) : NULL;
    int result = unpack_kindle(input, directory);
    if (directory) (*env)->ReleaseStringUTFChars(env, target, directory);
    if (input) (*env)->ReleaseStringUTFChars(env, source, input);
    return result;
}
#endif
