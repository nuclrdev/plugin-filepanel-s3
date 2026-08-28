/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.s3.api;

import dev.nuclr.plugin.core.panel.s3.S3Error;

/**
 * The outcome of one S3 call: a value, or a classified {@link S3Error}.
 *
 * <p>Every call in {@link S3Client} answers with one of these rather than throwing. A file panel is
 * a place where failure is ordinary — a bucket you cannot list, an object someone deleted a moment
 * ago, a session that expired while you were reading — and those want handling at the call site,
 * beside the row that could not be shown, not unwinding to a catch block somewhere else.
 *
 * @param <T> the value type on success
 */
public sealed interface S3Result<T> {

	/**
	 * A successful call.
	 *
	 * @param value the result value
	 * @param <T>   the value type
	 */
	record Ok<T>(T value) implements S3Result<T> {}

	/**
	 * A failed call.
	 *
	 * @param error what went wrong
	 * @param <T>   the value type that would have been returned
	 */
	record Err<T>(S3Error error) implements S3Result<T> {}

	/**
	 * Wrap a value as a success.
	 *
	 * @param value the value
	 * @param <T>   the value type
	 * @return the successful result
	 */
	static <T> S3Result<T> ok(T value) {
		return new Ok<>(value);
	}

	/**
	 * Wrap an error as a failure.
	 *
	 * @param error the error
	 * @param <T>   the value type that would have been returned
	 * @return the failed result
	 */
	static <T> S3Result<T> err(S3Error error) {
		return new Err<>(error);
	}

	/**
	 * Whether this result carries a value.
	 *
	 * @return {@code true} for a success
	 */
	default boolean isOk() {
		return this instanceof Ok<T>;
	}

	/**
	 * The value, or {@code null} for a failure.
	 *
	 * @return the value when successful
	 */
	default T orNull() {
		return this instanceof Ok<T> ok ? ok.value() : null;
	}

	/**
	 * The error, or {@code null} for a success.
	 *
	 * @return the error when failed
	 */
	default S3Error errorOrNull() {
		return this instanceof Err<T> err ? err.error() : null;
	}

	/**
	 * Whether this failed because the user cancelled.
	 *
	 * @return {@code true} for a cancellation
	 */
	default boolean isCancelled() {
		return this instanceof Err<T> err && err.error() instanceof S3Error.Cancelled;
	}

	/**
	 * Re-wrap this failure for a different value type, so an error can be propagated out of a call
	 * whose success type differs.
	 *
	 * @param <R> the new value type
	 * @return the same error under the new type
	 * @throws IllegalStateException if called on a success
	 */
	default <R> S3Result<R> propagate() {
		if (this instanceof Err<T> err) {
			return new Err<>(err.error());
		}
		throw new IllegalStateException("Cannot propagate a successful result as an error");
	}
}
