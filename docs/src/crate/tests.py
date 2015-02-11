import unittest
import doctest
import zc.customdoctests
from crate.testing.layer import CrateLayer
import os
import shutil
import re
import process_test
from .paths import crate_path, project_path
from .ports import random_available_port
from crate.crash.command import CrateCmd
from crate.crash.printer import PrintWrapper, ColorPrinter
from crate.client import connect


class CrateTestCmd(CrateCmd):

    def __init__(self, **kwargs):
        super(CrateTestCmd, self).__init__(**kwargs)
        doctest_print = PrintWrapper()
        self.logger = ColorPrinter(False, stream=doctest_print, line_end='')

    def stmt(self, stmt):
        stmt = stmt.replace('\n', ' ')
        if stmt.startswith('\\'):
            self.process(stmt)
        else:
            self.execute(stmt)

cmd = CrateTestCmd(is_tty=False)


CRATE_HTTP_PORT = random_available_port()
CRATE_TRANSPORT_PORT = random_available_port()


def wait_for_schema_update(schema, table, column):
    conn = connect('localhost:' + str(CRATE_HTTP_PORT))
    c = conn.cursor()
    count = 0
    while count == 0:
        c.execute(('select count(*) from information_schema.columns '
                    'where schema_name = ? and table_name = ? '
                    'and column_name = ?'),
                    (schema, table, column))
        count = c.fetchone()[0]


def bash_transform(s):
    # The examples in the docs show the real port '4200' to a reader.
    # Our test suite requires the port to be '44200' to avoid conflicts.
    # Therefore, we need to replace the ports before a test is being run.
    s = s.replace(':4200/', ':{0}/'.format(CRATE_HTTP_PORT))
    if s.startswith("crash"):
        s = re.search(r"crash\s+-c\s+\"(.*?)\"", s).group(1)
        return u'cmd.stmt({0})'.format(repr(s.strip().rstrip(';')))
    return (
        r'import subprocess;'
        r'print(subprocess.check_output(r"""%s""",stderr=subprocess.STDOUT,shell=True))' % s) + '\n'


def crash_transform(s):
    # The examples in the docs show the real port '4200' to a reader.
    # Our test suite requires the port to be '44200' to avoid conflicts.
    # Therefore, we need to replace the ports before a test is being run.
    if s.startswith('_'):
        return s[1:]
    s = s.replace(':4200', ':{0}'.format(CRATE_HTTP_PORT))
    return u'cmd.stmt({0})'.format(repr(s.strip().rstrip(';')))


bash_parser = zc.customdoctests.DocTestParser(
    ps1='sh\$', comment_prefix='#', transform=bash_transform)

crash_parser = zc.customdoctests.DocTestParser(
    ps1='cr>', comment_prefix='#', transform=crash_transform)


class ConnectingCrateLayer(CrateLayer):

    def start(self):
        super(ConnectingCrateLayer, self).start()
        cmd._connect(self.crate_servers[0])

empty_layer = ConnectingCrateLayer('crate',
                         crate_home=crate_path(),
                         crate_exec=crate_path('bin', 'crate'),
                         port=CRATE_HTTP_PORT,
                         transport_port=CRATE_TRANSPORT_PORT)


def setUpLocations(test):
    test.globs['cmd'] = cmd
    cmd.stmt("""
        create table locations (
          id string primary key,
          name string,
          date timestamp,
          kind string,
          position integer,
          description string,
          race object(dynamic) as (
            interests array(string)
          ),
          information array(object as (
              population long,
              evolution_level short
            )
          ),
          index name_description_ft using fulltext(name, description) with (analyzer='english')
        ) clustered by(id) into 2 shards with (number_of_replicas=0)""".strip())
    cmd.stmt("delete from locations")
    locations_file = os.path.abspath(os.path.join(os.path.dirname(__file__), "locations.json"))
    cmd.stmt("""copy locations from '{0}'""".format(locations_file))
    cmd.stmt("""refresh table locations""")


def setUpUserVisits(test):
    test.globs['cmd'] = cmd
    cmd.stmt("""
        create table uservisits(
          id integer primary key,
          name string,
          visits integer,
          last_visit timestamp
        )
    """.strip())
    uservisits_file = os.path.abspath(os.path.join(os.path.dirname(__file__), "uservisits.json"))
    cmd.stmt("""copy uservisits from '{0}'""".format(uservisits_file))
    cmd.stmt("""refresh table uservisits""")


def setUpQuotes(test):
    test.globs['cmd'] = cmd
    cmd.stmt("""
        create table quotes (
          id integer primary key,
          quote string
        ) clustered by(id) into 2 shards with(number_of_replicas=0)""")

    import_dir = '/tmp/import_data'
    if not os.path.isdir(import_dir):
        os.mkdir(import_dir)
    shutil.copy(project_path('sql/src/test/resources/essetup/data/copy', 'test_copy_from.json'),
                os.path.join(import_dir, "quotes.json"))


def setUpLocationsAndQuotes(test):
    setUpLocations(test)
    setUpQuotes(test)


def setUpLocationsQuotesAndUserVisits(test):
    setUpLocationsAndQuotes(test)
    setUpUserVisits(test)


def setUpTutorials(test):
    setUp(test)
    import_dir = '/tmp/best_practice_data'
    source_dir = 'sql/src/test/resources/essetup/data/best_practice'
    if not os.path.isdir(import_dir):
        os.mkdir(import_dir)
    shutil.copy(project_path(source_dir, 'data_import.json'),
                os.path.join(import_dir, "users.json"))
    shutil.copy(project_path(source_dir, 'data_import.json.gz'),
                os.path.join(import_dir, "users.json.gz"))
    shutil.copy(project_path(source_dir, 'data_import_1408312800.json'),
                os.path.join(import_dir, "users_1408312800.json"))


def setUp(test):
    test.globs['cmd'] = cmd
    test.globs['wait_for_schema_update'] = wait_for_schema_update


def tearDownDropQuotes(test):
    cmd.stmt("drop table quotes")


def test_suite():
    suite = unittest.TestSuite()
    processSuite = unittest.TestLoader().loadTestsFromModule(process_test)
    suite.addTest(processSuite)
    s = doctest.DocFileSuite('../../blob.txt',
                             parser=bash_parser,
                             setUp=setUp,
                             tearDown=tearDownDropQuotes,
                             optionflags=doctest.NORMALIZE_WHITESPACE |
                             doctest.ELLIPSIS)
    s.layer = empty_layer
    suite.addTest(s)
    for fn in ('sql/rest.txt',):
        s = doctest.DocFileSuite('../../' + fn,
                                 parser=bash_parser,
                                 setUp=setUpLocations,
                                 optionflags=doctest.NORMALIZE_WHITESPACE |
                                 doctest.ELLIPSIS)
        s.layer = empty_layer
        suite.addTest(s)
    for fn in ('sql/ddl.txt',
               'sql/dql.txt',
               'sql/refresh.txt',
               'sql/fulltext.txt',
               'sql/data_types.txt',
               'sql/occ.txt',
               'sql/information_schema.txt',
               'sql/partitioned_tables.txt',
               'sql/aggregation.txt',
               'sql/arithmetic.txt',
               'sql/scalar.txt',
               'sql/system.txt',
               'sql/queries.txt',
               'hello.txt'):
        s = doctest.DocFileSuite('../../' + fn, parser=crash_parser,
                                 setUp=setUpLocationsAndQuotes,
                                 optionflags=doctest.NORMALIZE_WHITESPACE |
                                 doctest.ELLIPSIS)
        s.layer = empty_layer
        suite.addTest(s)
    for fn in ('sql/dml.txt',):
        s = doctest.DocFileSuite('../../' + fn, parser=crash_parser,
                                 setUp=setUpLocationsQuotesAndUserVisits,
                                 optionflags=doctest.NORMALIZE_WHITESPACE |
                                 doctest.ELLIPSIS)
        s.layer = empty_layer
        suite.addTest(s)
    for fn in ('best_practice/migrating_from_mongodb.txt',):
        path = os.path.join('..', '..', fn)
        s = doctest.DocFileSuite(path, parser=crash_parser,
                                 setUp=setUp,
                                 optionflags=doctest.NORMALIZE_WHITESPACE |
                                 doctest.ELLIPSIS)
        s.layer = empty_layer
        suite.addTest(s)
    for fn in ('data_import.txt', 'cluster_upgrade.txt'):
        path = os.path.join('..', '..', 'best_practice', fn)
        s = doctest.DocFileSuite(path, parser=crash_parser,
                                 setUp=setUpTutorials,
                                 optionflags=doctest.NORMALIZE_WHITESPACE |
                                 doctest.ELLIPSIS)
        s.layer = empty_layer
        suite.addTest(s)
    return suite
