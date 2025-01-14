/*
 * Copyright French Prime minister Office/SGMAP/DINSIC/Vitam Program (2015-2019)
 *
 * contact.vitam@culture.gouv.fr
 *
 * This software is a computer program whose purpose is to implement a digital archiving back-office system managing
 * high volumetry securely and efficiently.
 *
 * This software is governed by the CeCILL 2.1 license under French law and abiding by the rules of distribution of free
 * software. You can use, modify and/ or redistribute the software under the terms of the CeCILL 2.1 license as
 * circulated by CEA, CNRS and INRIA at the following URL "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and rights to copy, modify and redistribute granted by the license,
 * users are provided only with a limited warranty and the software's author, the holder of the economic rights, and the
 * successive licensors have only limited liability.
 *
 * In this respect, the user's attention is drawn to the risks associated with loading, using, modifying and/or
 * developing or reproducing the software by the user in light of its specific status of free software, that may mean
 * that it is complicated to manipulate, and that also therefore means that it is reserved for developers and
 * experienced professionals having in-depth computer knowledge. Users are therefore encouraged to load and test the
 * software's suitability as regards their requirements in conditions enabling the security of their systems and/or data
 * to be ensured and, more generally, to use and operate it in the same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had knowledge of the CeCILL 2.1 license and that you
 * accept its terms.
 */
package fr.gouv.vitam.worker.core.distribution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import fr.gouv.vitam.common.collection.CloseableIterator;
import fr.gouv.vitam.common.exception.InvalidParseOperationException;
import fr.gouv.vitam.common.json.JsonHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Spliterator.ORDERED;

public class JsonLineGenericIterator<T> implements CloseableIterator<T> {
    private final BufferedReader bufferedReader;
    private final Iterator<String> lineIterator;
    private final TypeReference<T> typeReference;

    public JsonLineGenericIterator(InputStream inputStream, TypeReference<T> typeReference) {
        bufferedReader = new BufferedReader(new InputStreamReader(inputStream, UTF_8));
        lineIterator = bufferedReader.lines().iterator();
        this.typeReference = typeReference;
    }

    @Override
    public boolean hasNext() {
        return lineIterator.hasNext();
    }

    @Override
    public T next() {
        try {
            return JsonHandler.getFromStringAsTypeRefence(lineIterator.next(), typeReference);
        } catch (InvalidParseOperationException | InvalidFormatException e) {
            throw new RuntimeException("Could not parse json line entry", e);
        }
    }

    @Override
    public void close() {
        try {
            bufferedReader.close();
        } catch (IOException e) {
            throw new RuntimeException("Could not close reader", e);
        }
    }

    public Spliterator<T> spliterator() {
        return Spliterators.spliterator(this, 0L, ORDERED);
    }

    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }
}
