# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import datetime
import decimal
import uuid
from typing import Optional, List, Any, Sequence, Tuple, Union

from pyignite3 import _pyignite3_extension
from pyignite3 import native_type_code

__version__ = '3.0.0-beta2'

# PEP 249 is supported
apilevel = '2.0'

# Threads may share the module, but not connections.
threadsafety = 1

# Parameter style is a question mark, e.g. '...WHERE name=?'
paramstyle = 'qmark'

NIL = None
BOOLEAN = bool
INT = int
FLOAT = float
STRING = str
BINARY = bytes
NUMBER = decimal.Decimal
DATE = datetime.date
TIME = datetime.time
DATETIME = datetime.datetime
DURATION = datetime.timedelta
UUID = uuid.UUID


class TIMESTAMP(float):
    pass


# noinspection PyPep8Naming
def Date(year, month, day):
    """
    This function constructs an object holding a date value.
    """
    return DATE(year=year, month=month, day=day)


# noinspection PyPep8Naming
def Time(hour, minute, second):
    """
    This function constructs an object holding a time value.
    """
    return TIME(hour=hour, minute=minute, second=second)


# noinspection PyPep8Naming
def Timestamp(year, month, day, hour, minute, second):
    """
    This function constructs an object holding a time stamp value.
    """
    dt = datetime.datetime(year=year, month=month, day=day, hour=hour, minute=minute, second=second)
    ts = dt.timestamp()
    return TIMESTAMP(ts)


# noinspection PyPep8Naming
def DateFromTicks(ticks):
    """
    This function constructs an object holding a date value from the given ticks value (number of seconds since
    the epoch; see the documentation of the standard Python time module for details).
    """
    return DATE.fromtimestamp(ticks)


# noinspection PyPep8Naming
def TimeFromTicks(ticks):
    """
    This function constructs an object holding a time value from the given ticks value (number of seconds since
    the epoch; see the documentation of the standard Python time module for details).
    """
    return DATETIME.fromtimestamp(ticks).time()


# noinspection PyPep8Naming
def TimestampFromTicks(ticks):
    """
    This function constructs an object holding a time stamp value from the given ticks value (number of seconds since
    the epoch; see the documentation of the standard Python time module for details).
    """
    return TIMESTAMP(ticks)


# noinspection PyPep8Naming
def Binary(string: str):
    """
    This function constructs an object capable of holding a binary (long) string value.
    """
    return BINARY(string, 'utf-8')


def _type_code_from_int(native: int):
    if native == native_type_code.NIL:
        return NIL
    elif native == native_type_code.BOOLEAN:
        return BOOLEAN
    elif (native == native_type_code.INT8 or native == native_type_code.INT16
          or native == native_type_code.INT32 or native == native_type_code.INT64):
        return INT
    elif native == native_type_code.FLOAT or native == native_type_code.DOUBLE:
        return FLOAT
    elif native == native_type_code.DECIMAL or native == native_type_code.NUMBER:
        return NUMBER
    elif native == native_type_code.DATE:
        return DATE
    elif native == native_type_code.TIME:
        return TIME
    elif native == native_type_code.DATETIME or native == native_type_code.TIMESTAMP:
        return DATETIME
    elif native == native_type_code.UUID:
        return UUID
    elif native == native_type_code.BITMASK:
        return INT
    elif native == native_type_code.STRING:
        return STRING
    elif native == native_type_code.BYTE_ARRAY:
        return BINARY
    elif native == native_type_code.PERIOD or native == native_type_code.DURATION:
        return DATETIME
    raise InterfaceError(f'Unsupported data type: {native}')


class ColumnDescription:
    def __init__(self, name: str, type_code: int, display_size: Optional[int], internal_size: Optional[int],
                 precision: Optional[int], scale: Optional[int], null_ok: bool):
        self.name = name
        self.type_code = _type_code_from_int(type_code)
        self.display_size = display_size
        self.internal_size = internal_size
        self.precision = precision
        self.scale = scale
        self.null_ok = null_ok


class Cursor:
    """
    Cursor class. Represents a single statement and holds the result of its execution.

    Attributes
    ----------
    arraysize: int
        a read/write attribute, that specifies the number of rows to fetch at a time with .fetchmany().
        It defaults to 1 meaning to fetch a single row at a time.
    """
    arraysize: int = 1

    def __init__(self, py_cursor):
        self._py_cursor = py_cursor
        self._description = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    @property
    def description(self) -> Optional[List[ColumnDescription]]:
        """
        This read-only attribute is a sequence of 7-item sequences.
        Each of these sequences contains information describing one result column:
        - name
        - type_code
        - display_size
        - internal_size
        - precision
        - scale
        - null_ok
        The first two items (name and type_code) are mandatory, the other five are optional and are set to None if
        no meaningful values can be provided.
        This attribute will be None for operations that do not return rows or if the cursor has not had an operation
        invoked via the .execute*() method yet.
        """
        if self._py_cursor is None:
            return None
        return self._description

    @property
    def rowcount(self) -> int:
        """
        This read-only attribute specifies the number of rows that the last .execute*() produced
        (for DQL statements like SELECT) or affected (for DML statements like UPDATE or INSERT).
        The attribute is -1 in case no .execute*() has been performed on the cursor or the rowcount of the last
        operation is cannot be determined by the interface.
        """
        if self._py_cursor is None:
            return -1
        return self._py_cursor.rowcount()

    def callproc(self, *args):
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        raise NotSupportedError('Stored procedures are not supported')

    def close(self):
        """
        Close active cursor.
        Completes without errors on successfully closed cursors.
        """
        if self._py_cursor is not None:
            self._py_cursor.close()
            self._py_cursor = None

    def execute(self, query: str, params: Optional[Union[List[Any], Tuple[Any]]] = None):
        """
        Execute a database operation (query or command).

        Parameters may be provided as sequence or mapping and will be bound to variables in the operation.
        Arguments are specified as a question mark '?' in the request.

        The parameters may also be specified as list of tuples to e.g. insert multiple rows in a single operation,
        but this kind of usage is deprecated: .executemany() should be used instead.
        """
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        self._py_cursor.execute(query, params)
        self._update_description()

    def _update_description(self):
        """
        Internal method.
        Update column description for the current cursor. To be called after query execution.
        """
        self._description = []
        for column_id in range(self._py_cursor.column_count()):
            self._description.append(ColumnDescription(
                name=self._py_cursor.column_name(column_id),
                type_code=self._py_cursor.column_type_code(column_id),
                display_size=self._py_cursor.column_display_size(column_id),
                internal_size=self._py_cursor.column_internal_size(column_id),
                precision=self._py_cursor.column_precision(column_id),
                scale=self._py_cursor.column_scale(column_id),
                null_ok=self._py_cursor.column_null_ok(column_id)
            ))

    def executemany(self, *args):
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        # TODO: IGNITE-22742 Implement execution with a batch of parameters
        raise NotSupportedError('Operation is not supported')

    def fetchone(self) -> Optional[Sequence[Optional[Any]]]:
        """
        Fetch the next row of a query result set, returning a single sequence, or None when no more data is available.
        An Error (or subclass) exception is raised if the previous call to .execute*() did not produce any result set
        or no call was issued yet.
        """
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        return self._py_cursor.fetchone()

    def fetchmany(self, size: Optional[int] = None) -> Optional[Sequence[Sequence[Optional[Any]]]]:
        """
        Fetch the next set of rows of a query result, returning a sequence of sequences. An empty sequence is returned
        when no more rows are available.

        The number of rows to fetch per call is specified by the parameter. If it is not given, the cursor’s arraysize
        determines the number of rows to be fetched. The method tries to fetch as many rows as indicated by the size
        parameter. If this is not possible due to the specified number of rows not being available, fewer rows will be
        returned.

        An Error (or subclass) exception is raised if the previous call to .execute*() did not produce any result set
        or no call was issued yet.
        """
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        if size is None:
            size = self.arraysize

        if size <= 0:
            raise InterfaceError(f'Size parameter should be positive [size={size}]')

        res = []
        for i in range(size):
            row = self.fetchone()
            if row is None:
                break
            res.append(row)

        return None if not res else res

    def fetchall(self) -> Optional[Sequence[Sequence[Optional[Any]]]]:
        """
        Fetch all remaining rows of a query result, returning them as a sequence of sequences.
        An Error (or subclass) exception is raised if the previous call to .execute*() did not produce any result set
        or no call was issued yet.
        """
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        res = []
        row = self.fetchone()
        while row is not None:
            res.append(row)
            row = self.fetchone()

        return None if not res else res

    def nextset(self):
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        # TODO: IGNITE-22743 Implement execution of SQL scripts
        raise NotSupportedError('Operation is not supported')

    def setinputsizes(self, *args):
        if self._py_cursor is None:
            raise InterfaceError('Connection is already closed')

        # TODO: IGNITE-22742 Implement execution with a batch of parameters
        raise NotSupportedError('Operation is not supported')

    def setoutputsize(self, *args):
        """
        This operation does nothing currently.
        """
        pass


class Connection:
    """
    Connection class. Represents a single connection to the Ignite cluster.
    """

    def __init__(self):
        self._py_connection = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def close(self):
        """
        Close active connection.
        Completes without errors on successfully closed connections.
        """
        if self._py_connection is not None:
            self._py_connection.close()
            self._py_connection = None

    def commit(self):
        if self._py_connection is None:
            raise InterfaceError('Connection is already closed')

        # TODO: IGNITE-22740 Implement transaction support
        raise NotSupportedError('Transactions are not supported')

    def rollback(self):
        if self._py_connection is None:
            raise InterfaceError('Connection is already closed')

        # TODO: IGNITE-22740 Implement transaction support
        raise NotSupportedError('Transactions are not supported')

    def cursor(self) -> Cursor:
        if self._py_connection is None:
            raise InterfaceError('Connection is already closed')
        return Cursor(self._py_connection.cursor())


def connect(address: [str], **kwargs) -> Connection:
    """
    Establish connection with the Ignite cluster.

    Parameters
    ----------
    address: [str]
        A list of addresses of cluster nodes for client to choose from. Used for initial connection and fail-over.

    Keyword Arguments
    ----------
    identity: str, optional
        An identifier to use for authentication. E.g. username.
    secret: str, optional
        A secret to use for authentication. E.g. password.
    schema: str, optional
        A schema name to be used by default. Default value: 'PUBLIC'.
    timezone: str, optional
        A timezone to use as a client's timezone. Used to correctly work with date/time values, received from client.
        By default, a server's timezone is used.
    page_size: int, optional
        A maximum number of rows, which are received or sent in a single request. Default value: 1024.
    timeout: int, optional
        A timeout in seconds to use for any network operation. Default value: 30.
    """
    return _pyignite3_extension.connect(address=address, **kwargs)


class Error(Exception):
    pass


# noinspection PyShadowingBuiltins
class Warning(Exception):
    pass


class InterfaceError(Error):
    pass


class DatabaseError(Error):
    pass


class InternalError(DatabaseError):
    pass


class OperationalError(DatabaseError):
    pass


class ProgrammingError(DatabaseError):
    pass


class IntegrityError(DatabaseError):
    pass


class DataError(DatabaseError):
    pass


class NotSupportedError(DatabaseError):
    pass
